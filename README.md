# java-demos

Java demo programs for debugging and profiling demonstrations.

Each demo lives in its own directory with self-contained build files, a
README with full instructions, and (where applicable) Python scripting
examples. `cd` into a demo directory to build, run, record, and debug.

## Prerequisites

- Java 8+
- Maven 3.6+ or Gradle 7+

## Demos

```
Directory                      Description
-----------------------------  -------------------------------------------------------
concurrent-modification/       Multi-threaded ArrayList modification causes
                               ConcurrentModificationException

reentrant-modification/        Single-threaded reentrant modification via synchronized
                               callbacks — all methods are synchronized yet it still
                               throws ConcurrentModificationException

wait-notify/                   Producer silently stops calling notify(), leaving its
                               consumer stuck in wait() forever

wheres-wally/                  Hides "Wally" among 5,000 generated names — target for
                               precise execution navigation and syscall analysis

mixed-hotspot/                 Four distinct CPU workloads that produce different
                               signatures in Java and native profilers

vega/                          Multi-threaded options analytics engine with an
                               intentional Black-Scholes bisection bug

spring-petclinic-microservices/  Instrumented Spring microservices for distributed
                               debugging with Undo
```

Each demo's README covers how to build, run, record with Undo, register an
MCP server, and debug with Claude Code.

## Quick Start

From any demo directory:

```bash
cd concurrent-modification
mvn compile exec:java       # Maven
gradle run                  # Gradle
```

## Recording with Undo

Set `LR4J_HOME` to your lr4j installation directory.

**Short-lived demos** record the entire execution and save on exit:

```bash
mvn compile exec:exec -Precord      # Maven
gradle runRecord                     # Gradle
```

**Vega** uses signal-controlled recording for long-running operation — see
the [Vega README](vega/README.md).

## Debugging with Claude Code

You can use [Claude Code](https://docs.anthropic.com/en/docs/claude-code) to
interactively debug Undo recordings via an MCP server. The general workflow is:

1. **Convert your license key** (if you have a binary `.key` file):
   ```bash
   ./scripts/convert_key.sh /path/to/binary.key /path/to/license.pem
   ```

2. **Record the demo** (see above).

3. **Register the recording as an MCP server** from the demo directory:
   ```bash
   claude mcp add DemoName \
       -e BRIDGELOG=DemoName-bridge.log \
       -- $LR4J_HOME/bin/lr4j_mcp \
       --input DemoName.undo \
       --key /path/to/license.pem
   ```

4. **Launch `claude`** from the demo directory — see each demo's README for
   suggested prompts.
