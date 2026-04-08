package io.undo.test.vega.feed;

import io.undo.test.vega.bus.MessageBus;
import io.undo.test.vega.model.OptionQuote;
import io.undo.test.vega.model.SecurityUniverse.UnderlyingSecurity;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simulates a market data feed from an options exchange. Each feed runs on its own thread and
 * publishes option quotes to the message bus.
 *
 * <p>The feed generates realistic-looking option prices using a simple model: underlying spot
 * drifts via geometric Brownian motion, and option prices are derived from a noisy Black-Scholes
 * estimate.
 */
public class ExchangeFeed implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeFeed.class);

    private static final long MILLIS_PER_YEAR = 365L * 24 * 60 * 60 * 1000;
    private static final int[] EXPIRY_DAYS = {7, 14, 30, 60, 90, 180};

    private final String exchangeName;
    private final Properties config;
    private final MessageBus<OptionQuote> quoteBus;
    private final List<UnderlyingSecurity> securities;
    private final Random random;
    private final AtomicBoolean running;
    private final AtomicLong sequenceNumber;
    private Thread feedThread;

    private int tickIntervalMs;
    private double spreadBps;
    private double spotDriftScale;

    // Live spot prices that drift over time
    private double[] spotPrices;

    public ExchangeFeed(
            String exchangeName,
            Properties config,
            MessageBus<OptionQuote> quoteBus,
            List<UnderlyingSecurity> securities) {
        this.exchangeName = exchangeName;
        this.config = config;
        this.quoteBus = quoteBus;
        this.securities = securities;
        this.random = new Random(exchangeName.hashCode());
        this.running = new AtomicBoolean(false);
        this.sequenceNumber = new AtomicLong(0);

        this.tickIntervalMs = Integer.parseInt(config.getProperty("feed.tick.interval.ms", "50"));
        this.spreadBps = Double.parseDouble(config.getProperty("feed.spread.bps", "15.0"));
        this.spotDriftScale =
                Double.parseDouble(config.getProperty("feed.spot.drift.scale", "0.0002"));

        this.spotPrices = new double[securities.size()];
        for (int i = 0; i < securities.size(); i++) {
            spotPrices[i] = securities.get(i).getReferencePrice();
        }
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            feedThread = new Thread(this, "feed-" + exchangeName);
            feedThread.setDaemon(true);
            feedThread.start();
            LOG.info(
                    "Feed [{}]: started, interval={}ms, spread={}bps",
                    exchangeName,
                    tickIntervalMs,
                    spreadBps);
        }
    }

    public void stop() {
        running.set(false);
        if (feedThread != null) {
            feedThread.interrupt();
            try {
                feedThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("Feed [{}]: stopped after {} ticks", exchangeName, sequenceNumber.get());
    }

    @Override
    public void run() {
        LOG.info("Feed [{}]: thread running", exchangeName);
        while (running.get()) {
            try {
                generateTick();
                Thread.sleep(tickIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("Feed [{}]: error generating tick", exchangeName, e);
            }
        }
        LOG.info("Feed [{}]: thread exiting", exchangeName);
    }

    private void generateTick() {
        // Pick a random underlying
        int secIdx = random.nextInt(securities.size());
        UnderlyingSecurity sec = securities.get(secIdx);

        // Drift the spot price
        double drift = spotDriftScale * random.nextGaussian();
        spotPrices[secIdx] *= (1.0 + drift);
        double spot = spotPrices[secIdx];

        // Pick a random expiry
        int daysToExpiry = EXPIRY_DAYS[random.nextInt(EXPIRY_DAYS.length)];
        long expiryMillis = System.currentTimeMillis() + (long) daysToExpiry * 24 * 60 * 60 * 1000;
        double timeToExpiry = daysToExpiry / 365.0;

        // Pick a random strike from the offsets
        double[] offsets = sec.getStrikeOffsets();
        double offset = offsets[random.nextInt(offsets.length)];
        double strike = Math.round(spot * offset * 100.0) / 100.0;

        // Pick call or put
        OptionQuote.OptionType type =
                random.nextBoolean() ? OptionQuote.OptionType.CALL : OptionQuote.OptionType.PUT;

        // Compute a rough theoretical price using simplified Black-Scholes
        double vol = sec.getAnnualVol() * (0.9 + 0.2 * random.nextDouble());
        double theoPrice = roughBlackScholes(spot, strike, timeToExpiry, vol, type);

        // Add spread
        double halfSpread = theoPrice * (spreadBps / 10000.0);
        double bidPrice = Math.max(0.01, theoPrice - halfSpread);
        double askPrice = theoPrice + halfSpread;

        // Add noise
        bidPrice *= (1.0 + 0.001 * random.nextGaussian());
        askPrice *= (1.0 + 0.001 * random.nextGaussian());
        bidPrice = Math.round(bidPrice * 100.0) / 100.0;
        askPrice = Math.round(askPrice * 100.0) / 100.0;
        if (bidPrice >= askPrice) {
            askPrice = bidPrice + 0.01;
        }

        String optionSymbol = formatOptionSymbol(sec.getSymbol(), strike, daysToExpiry, type);
        long now = System.nanoTime();
        long seq = sequenceNumber.incrementAndGet();

        // Publish bid and ask
        OptionQuote bid =
                new OptionQuote(
                        exchangeName,
                        sec.getSymbol(),
                        optionSymbol,
                        type,
                        strike,
                        expiryMillis,
                        OptionQuote.Side.BID,
                        bidPrice,
                        10 + random.nextInt(90),
                        now,
                        seq);
        OptionQuote ask =
                new OptionQuote(
                        exchangeName,
                        sec.getSymbol(),
                        optionSymbol,
                        type,
                        strike,
                        expiryMillis,
                        OptionQuote.Side.ASK,
                        askPrice,
                        10 + random.nextInt(90),
                        now,
                        seq + 1);
        sequenceNumber.incrementAndGet();

        quoteBus.publish(bid);
        quoteBus.publish(ask);
    }

    private double roughBlackScholes(
            double spot, double strike, double t, double vol, OptionQuote.OptionType type) {
        if (t <= 0) return Math.max(0, spot - strike);

        double d1 = (Math.log(spot / strike) + 0.5 * vol * vol * t) / (vol * Math.sqrt(t));
        double d2 = d1 - vol * Math.sqrt(t);
        double nd1 = normalCDF(d1);
        double nd2 = normalCDF(d2);

        if (type == OptionQuote.OptionType.CALL) {
            return spot * nd1 - strike * nd2;
        } else {
            return strike * (1.0 - nd2) - spot * (1.0 - nd1);
        }
    }

    private double normalCDF(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private double erf(double x) {
        // Abramowitz and Stegun approximation 7.1.26
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double poly =
                t
                        * (0.254829592
                                + t
                                        * (-0.284496736
                                                + t
                                                        * (1.421413741
                                                                + t
                                                                        * (-1.453152027
                                                                                + t
                                                                                        * 1.061405429))));
        double result = 1.0 - poly * Math.exp(-x * x);
        return x >= 0 ? result : -result;
    }

    private String formatOptionSymbol(
            String underlying, double strike, int daysToExpiry, OptionQuote.OptionType type) {
        return String.format(
                "%s_%d_%s_%.0f",
                underlying, daysToExpiry, type == OptionQuote.OptionType.CALL ? "C" : "P", strike);
    }

    public String getExchangeName() {
        return exchangeName;
    }

    public long getTickCount() {
        return sequenceNumber.get();
    }
}
