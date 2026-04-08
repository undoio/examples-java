package io.undo.test.vega.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration system that loads hierarchical property files. Loads a common base config, then
 * overlays per-feed configs on top.
 *
 * <p>Property files are loaded from the classpath (src/main/resources). This mirrors the pattern of
 * per-environment, per-exchange configuration commonly found in trading systems.
 */
public class VegaConfig {

    private static final Logger LOG = LoggerFactory.getLogger(VegaConfig.class);

    private final Properties commonConfig;
    private final Map<String, Properties> feedConfigs;

    public VegaConfig() {
        this.commonConfig = new Properties();
        this.feedConfigs = new LinkedHashMap<>();
    }

    public void loadCommon(String resourcePath) {
        loadProperties(commonConfig, resourcePath);
        LOG.info("Loaded common config: {}", resourcePath);
    }

    public void loadFeed(String feedName, String resourcePath) {
        Properties feedProps = new Properties();
        // Start with common as defaults
        feedProps.putAll(commonConfig);
        // Overlay feed-specific
        loadProperties(feedProps, resourcePath);
        feedConfigs.put(feedName, feedProps);
        LOG.info("Loaded feed config: {} from {}", feedName, resourcePath);
    }

    private void loadProperties(Properties props, String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("Config file not found on classpath: {}", resourcePath);
                return;
            }
            props.load(is);
        } catch (IOException e) {
            LOG.error("Failed to load config: {}", resourcePath, e);
        }
    }

    public Properties getCommonConfig() {
        return commonConfig;
    }

    public Properties getFeedConfig(String feedName) {
        Properties config = feedConfigs.get(feedName);
        if (config == null) {
            LOG.warn("No config for feed '{}', using common defaults", feedName);
            return commonConfig;
        }
        return config;
    }

    public List<String> getFeedNames() {
        return new ArrayList<>(feedConfigs.keySet());
    }

    public String get(String key, String defaultValue) {
        return commonConfig.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String val = commonConfig.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Invalid int for key '{}': '{}'", key, val);
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        String val = commonConfig.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Invalid double for key '{}': '{}'", key, val);
            return defaultValue;
        }
    }

    public void logConfig() {
        LOG.info("=== Vega Configuration ===");
        LOG.info("Common properties: {}", commonConfig.size());
        for (String key : new TreeSet<>(commonConfig.stringPropertyNames())) {
            LOG.info("  {} = {}", key, commonConfig.getProperty(key));
        }
        for (Map.Entry<String, Properties> entry : feedConfigs.entrySet()) {
            LOG.info("Feed [{}]: {} properties", entry.getKey(), entry.getValue().size());
        }
    }
}
