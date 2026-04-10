# Vega - Real-Time Options Analytics Engine

A multi-threaded Java application that simulates a real-time options analytics
pipeline. Vega consumes simulated market data feeds from multiple exchanges,
computes implied volatility using Black-Scholes, and maintains live volatility
surfaces.

This is representative of the kind of infrastructure found in quantitative
trading systems: multi-threaded processing, per-exchange configuration,
internal pub/sub messaging, and lightweight management protocols.

## Architecture

```
                    +----------+     +----------+     +----------+
                    | Feed     |     | Feed     |     | Feed     |
                    | ALPHA    |     | BETA     |     | GAMMA    |
                    +----+-----+     +----+-----+     +----+-----+
                         |                |                |
                         +-------+--------+--------+-------+
                                 |                 |
                           +-----v-----+           |
                           | Quote Bus |<----------+
                           +-----+-----+
                                 |
                 +-------+-------+-------+-------+
                 |       |       |       |       |
              +--v--+ +--v--+ +--v--+ +--v--+   |
              |Vol  | |Vol  | |Vol  | |Vol  |   |
              |Wkr 0| |Wkr 1| |Wkr 2| |Wkr 3|  |
              +--+--+ +--+--+ +--+--+ +--+--+   |
                 |       |       |       |       |
                 +-------+-------+-------+       |
                         |                       |
                   +-----v------+                |
                   | Result Bus |                |
                   +-----+------+                |
                         |                       |
                   +-----v--------+    +---------v-------+
                   | Result       |    | Status Server   |
                   | Collector    |    | (TCP port 9876) |
                   | (Vol Surface)|    +-----------------+
                   +--------------+
```

### Components

- **Exchange Feeds** (`feed/`): Simulate market data from multiple exchanges.
  Each feed runs on its own thread and generates realistic option quotes with
  configurable tick rates and spread characteristics.

- **Message Bus** (`bus/`): In-process pub/sub system using blocking queues.
  Decouples feed handlers from processing engines — publishers push quotes
  to a shared queue, a dispatch thread fans out to all subscribers.

- **Vol Engine** (`engine/`): Worker threads that consume option quotes, pair
  bids and asks, and compute implied volatility via Newton-Raphson on
  Black-Scholes. Includes full Greeks (delta, gamma, theta, vega).

- **Result Collector** (`engine/`): Aggregates vol results into per-underlying
  vol surfaces and periodically logs summary statistics.

- **Status Server** (`protocol/`): Lightweight TCP server for querying engine
  state. Connect with `telnet localhost 9876` and use commands like `STATUS`
  or `SURFACE SPY`.

- **Configuration** (`config/`): Hierarchical property-file system. A common
  base config is overlaid with per-feed settings.

## Prerequisites

- Java 8 or later
- Maven 3.6+ or Gradle 7+

## Building

```bash
cd vega
mvn clean package    # Maven
gradle build         # Gradle
```

## Running

```bash
# Maven
mvn exec:java

# Gradle
gradle run

# Via JAR
java -jar target/vega-1.0-SNAPSHOT.jar

# With custom config
java -Dvega.conf.file=custom.properties -jar target/vega-1.0-SNAPSHOT.jar
```

## Querying Status

While the server is running, connect to the status port:

```bash
telnet localhost 9876
```

Available commands:
- `STATUS` — overall engine metrics (feeds, workers, bus stats)
- `SURFACE SPY` — current vol surface for a given underlying
- `QUIT` — close connection

## Configuration

Configuration uses hierarchical property files in `src/main/resources/`:

| File | Purpose |
|------|---------|
| `common.properties` | Base settings shared across all components |
| `feed-alpha.properties` | High-frequency, tight-spread exchange feed |
| `feed-beta.properties` | Moderate-frequency exchange feed |
| `feed-gamma.properties` | Lower-frequency, wider-spread feed |

Key settings:

| Property | Default | Description |
|----------|---------|-------------|
| `engine.worker.count` | 4 | Number of vol computation worker threads |
| `bus.quote.capacity` | 100000 | Quote message bus queue capacity |
| `feed.tick.interval.ms` | 50 | Milliseconds between generated ticks |
| `feed.spread.bps` | 15.0 | Bid-ask spread in basis points |
| `log.summary.interval.ms` | 10000 | How often to log vol surface summaries |

## Debugging Exercise

This application contains a deliberately introduced bug in the Black-Scholes
bisection fallback (`BlackScholes.bisectionImpliedVol()`). The convergence
direction is inverted: when the computed price is above the market price, the
code raises the lower bound instead of lowering the upper bound, and vice
versa. This causes bisection to diverge instead of converge.

The bug only affects options where Newton-Raphson doesn't converge (deep
out-of-the-money options with short expiry, roughly 5-10% of quotes). These
produce an implied volatility of ~500% instead of the correct value. The
worker threads detect and log these as suspicious.

This is designed as a time-travel debugging exercise:

1. **Detect**: Set a breakpoint at the suspicious IV warning in `VolWorker` —
   observe IV = 5.0 (clearly wrong for equity options)
2. **Reverse to effect**: Reverse-continue into `bisectionImpliedVol` return —
   observe that `lo` and `hi` have both diverged to 5.0 instead of converging
3. **Reverse to cause**: Reverse-continue to the comparison at line 105 —
   observe `bsPrice >> marketPrice` but `lo = mid` is executed instead of
   `hi = mid`

### Recording with Undo

Vega is a long-running server, so rather than recording the entire execution
from startup, use signal-controlled recording to capture a window of
steady-state operation. The lr4j agent loads but does not record until you
tell it to via a command file and SIGQUIT.

**1. Launch with the recording agent:**

```bash
# Maven
mvn compile exec:exec -Precord

# Gradle
gradle runRecord
```

The command file path is printed on startup (Gradle) or is `vega_cmd.txt` in
the project directory (Maven). Set `LR4J_HOME` to your lr4j installation.

**2. Wait for steady state** (a few seconds for feeds to start publishing).

**3. Start recording:**
```bash
echo START > /path/to/command_file.txt
kill -3 $(pgrep -f VegaServer)
```

**4. Let it run** for 10-30 seconds to capture enough activity (including the bug).

**5. Save the recording and stop:**
```bash
echo 'SAVE_AND_STOP vega.undo' > /path/to/command_file.txt
kill -3 $(pgrep -f VegaServer)
```

`kill -3` sends SIGQUIT, which tells the lr4j agent to read the command file.
`START` begins recording and `SAVE_AND_STOP` writes the recording to disk.
This is the same mechanism used in production to capture snapshots of
long-running services without restarting them.

### Debugging with Claude Code

You can use [Claude Code](https://docs.anthropic.com/en/docs/claude-code) to
interactively debug an Undo recording of Vega via an MCP server. Record the
application using the steps above, then register the recording:

```bash
claude mcp add VegaServer \
    -e BRIDGELOG=VegaServer-bridge.log \
    -- $LR4J_HOME/lr4j-replay-1.0/lr4j/lr4j_mcp \
    --input VegaServer.undo \
    --key /path/to/license.pem
```

Then launch `claude` and ask:

> You have access to a recording of a Java application called Vega — a
> real-time options analytics engine. Something is going wrong at runtime
> but we're not sure what. Start by reading the console output to understand
> what the application is doing and look for anything suspicious. Then use
> the time-travel debugging tools to work backwards from any anomalies you
> find to identify the root cause in the source code.

Claude will discover the suspicious IV warnings, trace them back through the
vol worker into the bisection method, and identify the swapped convergence
bounds.

## Project Structure

```
vega/
├── pom.xml
├── README.md
└── src/
    └── main/
        ├── java/io/undo/test/vega/
        │   ├── model/          # Domain objects (OptionQuote, VolResult, SecurityUniverse)
        │   ├── feed/           # Simulated exchange feeds
        │   ├── bus/            # Internal pub/sub message bus
        │   ├── engine/         # Black-Scholes, vol workers, result collector
        │   ├── config/         # Property-file configuration
        │   ├── protocol/       # TCP status server
        │   └── server/         # Main entry point (VegaServer)
        └── resources/
            ├── common.properties
            ├── feed-alpha.properties
            ├── feed-beta.properties
            ├── feed-gamma.properties
            └── log4j.properties
```
