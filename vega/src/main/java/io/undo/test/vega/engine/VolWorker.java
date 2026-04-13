package io.undo.test.vega.engine;

import io.undo.test.vega.bus.MessageBus;
import io.undo.test.vega.model.OptionQuote;
import io.undo.test.vega.model.VolResult;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker that subscribes to the quote bus, pairs bids/asks, and computes implied volatility for
 * each option. Results are published to a results bus.
 *
 * <p>Each worker runs on its own thread, processing quotes from an internal queue. Workers are
 * partitioned by underlying symbol so that each worker owns a slice of the universe and processes
 * it independently — the typical pattern in a real vol engine.
 */
public class VolWorker implements MessageBus.Subscriber<OptionQuote>, Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(VolWorker.class);

    private static final double RISK_FREE_RATE = 0.05;
    private static final long MILLIS_PER_YEAR = 365L * 24 * 60 * 60 * 1000;

    private final String workerId;
    private final MessageBus<VolResult> resultBus;
    private final LinkedBlockingQueue<OptionQuote> inboundQueue;
    private final Map<String, OptionQuote> latestBids;
    private final Map<String, OptionQuote> latestAsks;
    private final Map<String, Double> spotPrices;
    private final Set<String> assignedUnderlyings;
    private final AtomicLong computeCount;
    private final AtomicLong errorCount;
    private final AtomicBoolean running;
    private Thread workerThread;

    public VolWorker(String workerId, MessageBus<VolResult> resultBus, int queueCapacity) {
        this.workerId = workerId;
        this.resultBus = resultBus;
        this.inboundQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.latestBids = new ConcurrentHashMap<>();
        this.latestAsks = new ConcurrentHashMap<>();
        this.spotPrices = new ConcurrentHashMap<>();
        this.assignedUnderlyings = new HashSet<>();
        this.computeCount = new AtomicLong(0);
        this.errorCount = new AtomicLong(0);
        this.running = new AtomicBoolean(false);
    }

    public void assignUnderlying(String symbol) {
        assignedUnderlyings.add(symbol);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this, workerId);
            workerThread.setDaemon(true);
            workerThread.start();
            LOG.info(
                    "Worker [{}]: started, assigned underlyings: {}",
                    workerId,
                    assignedUnderlyings);
        }
    }

    public void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info(
                "Worker [{}]: stopped, computed={}, errors={}",
                workerId,
                computeCount.get(),
                errorCount.get());
    }

    @Override
    public void onMessage(OptionQuote quote) {
        // Filter: only enqueue quotes for our assigned underlyings
        if (!assignedUnderlyings.contains(quote.getUnderlying())) {
            return;
        }
        if (!inboundQueue.offer(quote)) {
            // Queue full — drop silently (bus-level stats track drops)
        }
    }

    @Override
    public void run() {
        LOG.info("Worker [{}]: thread running", workerId);
        while (running.get()) {
            try {
                OptionQuote quote = inboundQueue.poll(100, TimeUnit.MILLISECONDS);
                if (quote != null) {
                    processQuote(quote);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                errorCount.incrementAndGet();
                LOG.error("Worker [{}]: unexpected error", workerId, e);
            }
        }
        LOG.info("Worker [{}]: thread exiting", workerId);
    }

    private void processQuote(OptionQuote quote) {
        String key = quote.getOptionSymbol();

        // Update latest quote
        if (quote.getSide() == OptionQuote.Side.BID) {
            latestBids.put(key, quote);
        } else {
            latestAsks.put(key, quote);
        }

        // Track spot as mid of ATM quotes (rough proxy)
        updateSpotEstimate(quote);

        // Try to compute IV if we have both sides
        OptionQuote bid = latestBids.get(key);
        OptionQuote ask = latestAsks.get(key);
        if (bid != null && ask != null) {
            computeImpliedVol(bid, ask);
        }
    }

    private void updateSpotEstimate(OptionQuote quote) {
        // Use the price as a rough spot proxy weighted toward ATM options
        double moneyness =
                Math.abs(
                        1.0
                                - quote.getStrike()
                                        / spotPrices.getOrDefault(
                                                quote.getUnderlying(), quote.getStrike()));
        if (moneyness < 0.05 || !spotPrices.containsKey(quote.getUnderlying())) {
            // Near-ATM option — use strike as spot estimate
            spotPrices.put(quote.getUnderlying(), quote.getStrike());
        }
    }

    private void computeImpliedVol(OptionQuote bid, OptionQuote ask) {
        try {
            double midPrice = (bid.getPrice() + ask.getPrice()) / 2.0;
            if (midPrice <= 0) return;

            Double spot = spotPrices.get(bid.getUnderlying());
            if (spot == null || spot <= 0) return;

            double timeToExpiry =
                    (bid.getExpiryEpochMillis() - System.currentTimeMillis())
                            / (double) MILLIS_PER_YEAR;
            if (timeToExpiry <= 0) return;

            long startNanos = System.nanoTime();
            double iv =
                    BlackScholes.impliedVol(
                            spot,
                            bid.getStrike(),
                            timeToExpiry,
                            RISK_FREE_RATE,
                            midPrice,
                            bid.getOptionType());
            long computeNanos = System.nanoTime() - startNanos;

            if (Double.isNaN(iv) || iv <= 0 || iv > 2.0) {
                LOG.warn("Worker [{}]: suspicious IV={} for {}", workerId, String.format("%.4f", iv), bid.getOptionSymbol());
                errorCount.incrementAndGet();
                return;
            }

            VolResult result =
                    new VolResult(
                            bid.getUnderlying(),
                            bid.getOptionSymbol(),
                            bid.getOptionType(),
                            bid.getStrike(),
                            bid.getExpiryEpochMillis(),
                            iv,
                            spot,
                            timeToExpiry,
                            midPrice,
                            computeNanos,
                            System.nanoTime(),
                            bid.getExchange());

            resultBus.publish(result);
            long count = computeCount.incrementAndGet();

            if (count % 5000 == 0) {
                LOG.info(
                        "Worker [{}]: computed {} vols, last IV={} for {} ({}us)",
                        workerId,
                        count,
                        String.format("%.4f", iv),
                        bid.getOptionSymbol(),
                        computeNanos / 1000);
            }
        } catch (Exception e) {
            errorCount.incrementAndGet();
            LOG.error("Worker [{}]: error computing IV for {}", workerId, bid.getOptionSymbol(), e);
        }
    }

    public String getWorkerId() {
        return workerId;
    }

    public long getComputeCount() {
        return computeCount.get();
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public int getQueueDepth() {
        return inboundQueue.size();
    }
}
