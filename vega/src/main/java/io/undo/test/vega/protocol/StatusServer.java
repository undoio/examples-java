package io.undo.test.vega.protocol;

import io.undo.test.vega.bus.MessageBus;
import io.undo.test.vega.engine.ResultCollector;
import io.undo.test.vega.engine.VolWorker;
import io.undo.test.vega.feed.ExchangeFeed;
import io.undo.test.vega.model.OptionQuote;
import io.undo.test.vega.model.VolResult;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple TCP status server that allows external tools to query the engine's state. Accepts
 * connections and responds to text commands.
 *
 * <p>Commands: STATUS - overall engine status SURFACE <underlying> - current vol surface for an
 * underlying QUIT - close connection
 *
 * <p>This is representative of the kind of lightweight management protocol found alongside the main
 * data path in trading systems.
 */
public class StatusServer implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(StatusServer.class);

    private final int port;
    private final ResultCollector collector;
    private final List<ExchangeFeed> feeds;
    private final List<VolWorker> workers;
    private final MessageBus<OptionQuote> quoteBus;
    private final MessageBus<VolResult> resultBus;
    private final AtomicBoolean running;
    private ServerSocket serverSocket;
    private Thread serverThread;

    public StatusServer(
            int port,
            ResultCollector collector,
            List<ExchangeFeed> feeds,
            List<VolWorker> workers,
            MessageBus<OptionQuote> quoteBus,
            MessageBus<VolResult> resultBus) {
        this.port = port;
        this.collector = collector;
        this.feeds = feeds;
        this.workers = workers;
        this.quoteBus = quoteBus;
        this.resultBus = resultBus;
        this.running = new AtomicBoolean(false);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            serverThread = new Thread(this, "status-server");
            serverThread.setDaemon(true);
            serverThread.start();
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warn("Error closing status server socket", e);
        }
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            LOG.info("Status server listening on port {}", port);

            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    Thread handler =
                            new Thread(
                                    () -> handleClient(client),
                                    "status-client-" + client.getRemoteSocketAddress());
                    handler.setDaemon(true);
                    handler.start();
                } catch (SocketException e) {
                    if (running.get()) {
                        LOG.error("Status server accept error", e);
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("Status server failed to start on port {}", port, e);
        }
    }

    private void handleClient(Socket client) {
        LOG.info("Status client connected: {}", client.getRemoteSocketAddress());
        try (BufferedReader in =
                        new BufferedReader(new InputStreamReader(client.getInputStream()));
                PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

            out.println("VEGA STATUS SERVER v1.0");
            out.println("Commands: STATUS, SURFACE <symbol>, QUIT");
            out.println("---");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim().toUpperCase();
                LOG.debug("Status command: {}", line);

                if (line.equals("QUIT") || line.equals("EXIT")) {
                    out.println("BYE");
                    break;
                } else if (line.equals("STATUS")) {
                    writeStatus(out);
                } else if (line.startsWith("SURFACE ")) {
                    String symbol = line.substring(8).trim();
                    writeSurface(out, symbol);
                } else {
                    out.println("UNKNOWN COMMAND: " + line);
                }
            }
        } catch (IOException e) {
            LOG.debug("Status client disconnected: {}", client.getRemoteSocketAddress());
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void writeStatus(PrintWriter out) {
        out.println("=== ENGINE STATUS ===");
        out.printf("Total vol results: %d%n", collector.getTotalResults());
        out.printf(
                "Quote bus: published=%d dispatched=%d dropped=%d depth=%d%n",
                quoteBus.getPublishedCount(),
                quoteBus.getDispatchedCount(),
                quoteBus.getDroppedCount(),
                quoteBus.getQueueDepth());
        out.printf(
                "Result bus: published=%d dispatched=%d dropped=%d depth=%d%n",
                resultBus.getPublishedCount(),
                resultBus.getDispatchedCount(),
                resultBus.getDroppedCount(),
                resultBus.getQueueDepth());

        out.println("--- Feeds ---");
        for (ExchangeFeed feed : feeds) {
            out.printf("  %s: %d ticks%n", feed.getExchangeName(), feed.getTickCount());
        }
        out.println("--- Workers ---");
        for (VolWorker worker : workers) {
            out.printf(
                    "  %s: %d computed, %d errors%n",
                    worker.getWorkerId(), worker.getComputeCount(), worker.getErrorCount());
        }
        out.println("=== END ===");
    }

    private void writeSurface(PrintWriter out, String symbol) {
        Map<Double, VolResult> surface = collector.getVolSurface().get(symbol);
        if (surface == null || surface.isEmpty()) {
            out.println("NO DATA FOR " + symbol);
            return;
        }

        out.printf("=== VOL SURFACE: %s (%d strikes) ===%n", symbol, surface.size());
        out.printf("%-10s %-8s %-10s %-10s %-10s%n", "Strike", "Type", "IV", "Mid", "Moneyness");
        for (VolResult r : surface.values()) {
            out.printf(
                    "%-10.1f %-8s %-10.4f %-10.2f %-10.4f%n",
                    r.getStrike(),
                    r.getOptionType(),
                    r.getImpliedVol(),
                    r.getOptionMidPrice(),
                    r.getMoneyness());
        }
        out.println("=== END ===");
    }
}
