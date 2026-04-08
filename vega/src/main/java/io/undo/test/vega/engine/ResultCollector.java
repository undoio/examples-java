package io.undo.test.vega.engine;

import io.undo.test.vega.bus.MessageBus;
import io.undo.test.vega.model.VolResult;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects vol results and maintains a live vol surface per underlying. Periodically logs summary
 * statistics.
 *
 * <p>In a real system, this would publish to a real-time data engine; here we maintain in-memory
 * tables and log summaries.
 */
public class ResultCollector implements MessageBus.Subscriber<VolResult> {

    private static final Logger LOG = LoggerFactory.getLogger(ResultCollector.class);

    // Underlying -> (strike -> latest IV)
    private final Map<String, Map<Double, VolResult>> volSurface;
    private final AtomicLong totalResults;
    private final long startTimeMillis;

    private volatile long lastLogTime;
    private final long logIntervalMs;

    public ResultCollector(long logIntervalMs) {
        this.volSurface = new ConcurrentHashMap<>();
        this.totalResults = new AtomicLong(0);
        this.startTimeMillis = System.currentTimeMillis();
        this.lastLogTime = System.currentTimeMillis();
        this.logIntervalMs = logIntervalMs;
    }

    @Override
    public void onMessage(VolResult result) {
        // Update vol surface
        volSurface
                .computeIfAbsent(result.getUnderlying(), k -> new ConcurrentHashMap<>())
                .put(result.getStrike(), result);

        long count = totalResults.incrementAndGet();
        long now = System.currentTimeMillis();

        // Periodic summary logging
        if (now - lastLogTime > logIntervalMs) {
            lastLogTime = now;
            logSummary();
        }
    }

    public void logSummary() {
        long uptimeSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000;
        long total = totalResults.get();
        double rate = uptimeSeconds > 0 ? (double) total / uptimeSeconds : 0;

        LOG.info(
                "=== Vol Surface Summary (uptime: {}s, total: {}, rate: {}/s) ===",
                uptimeSeconds,
                total,
                String.format("%.1f", rate));

        for (Map.Entry<String, Map<Double, VolResult>> entry :
                new TreeMap<>(volSurface).entrySet()) {
            String underlying = entry.getKey();
            Map<Double, VolResult> strikes = entry.getValue();

            double avgIV =
                    strikes.values().stream()
                            .mapToDouble(VolResult::getImpliedVol)
                            .average()
                            .orElse(0);
            double minIV =
                    strikes.values().stream().mapToDouble(VolResult::getImpliedVol).min().orElse(0);
            double maxIV =
                    strikes.values().stream().mapToDouble(VolResult::getImpliedVol).max().orElse(0);

            VolResult sample = strikes.values().iterator().next();
            LOG.info(
                    "  {} : spot={}, strikes={}, avgIV={}%, range=[{}%-{}%]",
                    underlying,
                    String.format("%.2f", sample.getUnderlyingPrice()),
                    strikes.size(),
                    String.format("%.2f", avgIV * 100),
                    String.format("%.2f", minIV * 100),
                    String.format("%.2f", maxIV * 100));
        }
    }

    public Map<String, Map<Double, VolResult>> getVolSurface() {
        return Collections.unmodifiableMap(volSurface);
    }

    public long getTotalResults() {
        return totalResults.get();
    }
}
