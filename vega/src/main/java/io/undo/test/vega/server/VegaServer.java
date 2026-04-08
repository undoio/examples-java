package io.undo.test.vega.server;

import io.undo.test.vega.bus.MessageBus;
import io.undo.test.vega.config.VegaConfig;
import io.undo.test.vega.engine.ResultCollector;
import io.undo.test.vega.engine.VolWorker;
import io.undo.test.vega.feed.ExchangeFeed;
import io.undo.test.vega.model.OptionQuote;
import io.undo.test.vega.model.SecurityUniverse;
import io.undo.test.vega.model.VolResult;
import io.undo.test.vega.protocol.StatusServer;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Vega Options Analytics Engine.
 *
 * <p>Startup sequence: 1. Load configuration (common + per-feed property files) 2. Create message
 * buses (quote bus, result bus) 3. Create and register vol worker threads 4. Create exchange feed
 * generators 5. Start the status server 6. Start all components 7. Run until shutdown signal
 *
 * <p>This follows the typical lifecycle of a trading server process: config -> wiring -> startup ->
 * steady state -> shutdown.
 */
public class VegaServer {

    private static final Logger LOG = LoggerFactory.getLogger(VegaServer.class);
    private static final String LOG_PREFIX = "VegaServer";

    private final VegaConfig config;
    private MessageBus<OptionQuote> quoteBus;
    private MessageBus<VolResult> resultBus;
    private List<ExchangeFeed> feeds;
    private List<VolWorker> workers;
    private ResultCollector collector;
    private StatusServer statusServer;

    public VegaServer(VegaConfig config) {
        this.config = config;
        this.feeds = new ArrayList<>();
        this.workers = new ArrayList<>();
    }

    public void initialize() {
        LOG.info("{}: initializing", LOG_PREFIX);

        config.logConfig();

        // Create message buses
        int quoteCapacity = config.getInt("bus.quote.capacity", 100000);
        int resultCapacity = config.getInt("bus.result.capacity", 50000);
        quoteBus = new MessageBus<>("quotes", quoteCapacity);
        resultBus = new MessageBus<>("results", resultCapacity);

        // Create vol workers with per-worker queues
        int workerCount = config.getInt("engine.worker.count", 4);
        int workerQueueCapacity = config.getInt("engine.worker.queue.capacity", 10000);
        List<SecurityUniverse.UnderlyingSecurity> securities = SecurityUniverse.getSecurities();

        for (int i = 0; i < workerCount; i++) {
            VolWorker worker = new VolWorker("vol-worker-" + i, resultBus, workerQueueCapacity);
            workers.add(worker);
            quoteBus.subscribe(worker);
        }

        // Partition underlyings across workers round-robin
        for (int i = 0; i < securities.size(); i++) {
            VolWorker worker = workers.get(i % workerCount);
            worker.assignUnderlying(securities.get(i).getSymbol());
        }
        LOG.info(
                "{}: created {} vol workers, partitioned {} underlyings",
                LOG_PREFIX,
                workerCount,
                securities.size());

        // Create result collector
        long logInterval = Long.parseLong(config.get("log.summary.interval.ms", "10000"));
        collector = new ResultCollector(logInterval);
        resultBus.subscribe(collector);

        // Create exchange feeds
        for (String feedName : config.getFeedNames()) {
            ExchangeFeed feed =
                    new ExchangeFeed(
                            feedName, config.getFeedConfig(feedName),
                            quoteBus, SecurityUniverse.getSecurities());
            feeds.add(feed);
        }
        LOG.info("{}: created {} exchange feeds", LOG_PREFIX, feeds.size());

        // Create status server
        int statusPort = config.getInt("status.server.port", 9876);
        statusServer = new StatusServer(statusPort, collector, feeds, workers, quoteBus, resultBus);

        LOG.info("{}: initialization complete", LOG_PREFIX);
    }

    public void start() {
        LOG.info("{}: starting", LOG_PREFIX);

        // Start buses first
        quoteBus.start();
        resultBus.start();

        // Start vol workers
        for (VolWorker worker : workers) {
            worker.start();
        }

        // Start status server
        statusServer.start();

        // Start feeds last (they produce data)
        for (ExchangeFeed feed : feeds) {
            feed.start();
        }

        LOG.info("{}: all components started", LOG_PREFIX);
    }

    public void shutdown() {
        LOG.info("{}: shutting down", LOG_PREFIX);

        // Stop feeds first (stop producing)
        for (ExchangeFeed feed : feeds) {
            feed.stop();
        }

        // Stop buses (drain remaining messages)
        quoteBus.stop();
        resultBus.stop();

        // Stop vol workers
        for (VolWorker worker : workers) {
            worker.stop();
        }

        // Stop status server
        statusServer.stop();

        // Final summary
        collector.logSummary();

        LOG.info("{}: shutdown complete", LOG_PREFIX);
    }

    public static void main(String[] args) {
        LOG.info("=== Vega Options Analytics Engine ===");
        LOG.info("Starting up...");

        // Determine config file from args or system property
        String configFile = System.getProperty("vega.conf.file", "common.properties");
        LOG.info("Configuration: vega.conf.file is {}", configFile);

        // Load configuration
        VegaConfig config = new VegaConfig();
        config.loadCommon("common.properties");

        // Load feed configurations
        config.loadFeed("ALPHA", "feed-alpha.properties");
        config.loadFeed("BETA", "feed-beta.properties");
        config.loadFeed("GAMMA", "feed-gamma.properties");

        // Create, initialize, and start the server
        VegaServer server = new VegaServer(config);
        server.initialize();
        server.start();

        // Register shutdown hook
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    LOG.info("Shutdown hook triggered");
                                    server.shutdown();
                                },
                                "shutdown-hook"));

        LOG.info("Vega server running. Press Ctrl+C to stop.");

        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            LOG.info("Main thread interrupted");
            server.shutdown();
        }
    }
}
