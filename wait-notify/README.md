# WaitNotify Demo

A producer silently stops calling `notify()`, leaving its consumer stuck in
`wait()` forever. This is hard to debug conventionally because:

- The stuck thread's stack just shows `wait()` — no clue **why**
- The producer that skipped `notify` has already finished
- With 5 pairs running concurrently, finding which producer caused it is a
  needle-in-a-haystack problem

## Design

Each of 5 producer/consumer pairs uses lockstep synchronization. The producer
sends work via `notify()`, the consumer processes it and acks. Halfway through,
one producer stops notifying — its consumer hangs.

## Prerequisites

- Java 8+
- Maven 3.6+ or Gradle 7+

## Build & Run

**Maven:**
```bash
mvn compile exec:java
```

**Gradle:**
```bash
gradle run
```

## Recording with Undo

Set `LR4J_HOME` to your lr4j installation directory.

**Maven:**
```bash
mvn compile exec:exec -Precord
```

**Gradle:**
```bash
gradle runRecord
```

On arm64 (Maven only):
```bash
mvn compile exec:exec -Precord -Dlr4j.agent=$LR4J_HOME/agent/lr4j_agent_arm64.so
```

## Debugging with Claude Code

**1. Register the recording as an MCP server:**

```bash
claude mcp add WaitNotify \
    -e BRIDGELOG=WaitNotify-bridge.log \
    -- $LR4J_HOME/bin/lr4j_mcp \
    --input WaitNotifyDemo.undo \
    --key /path/to/license.pem
```

**2. Launch `claude` from this directory and ask:**

```
Read the console messages, figure out what happened, and go to the root cause.
```

Claude will read the program output, identify the stuck consumer thread,
set appropriate breakpoints and watchpoints, and use time-travel debugging to
find the exact moment the producer stopped calling `notify()`.

## Scripting

The `scripts/` directory contains Python scripts that use Undo's scripting API
to automatically diagnose the bug from a recording.

```bash
cd $LR4J_HOME
export PYTHONPATH=demos/python

python3 /path/to/wait-notify/scripts/wait_notify_simple.py \
    ./lr4j_mcp \
    /path/to/recording.undo \
    /path/to/license.key
```

### Adapting for Your Code

The scripts reference WaitNotifyDemo class names and line numbers. To adapt
for a different application:

```
What to find                    What to change
------------------------------  ------------------------------------------
'Consumer-'                     Your thread name prefix
WaitNotifyDemo$Consumer         Your fully-qualified consumer class
WaitNotifyDemo$Producer         Your fully-qualified producer class
PRODUCER_SKIP_LINE = 239        Line number where the bug occurs
fieldName='consumer'            Field name linking producer to consumer
```
