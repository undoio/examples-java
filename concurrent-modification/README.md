# ConcurrentModificationException Demo

Multi-threaded modification of a shared ArrayList causes
ConcurrentModificationException. A background thread spawns mutators that
add/remove elements while the main thread iterates over the same list.

This is a classic Java concurrency bug — the exception is thrown by the
fail-fast iterator when it detects structural modification during iteration.

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
claude mcp add ConcurrentModification \
    -e BRIDGELOG=ConcurrentModification-bridge.log \
    -- $LR4J_HOME/bin/lr4j_mcp \
    --input ConcurrentModificationExceptionDemo.undo \
    --key /path/to/license.pem
```

**2. Launch `claude` from this directory and ask:**

```
Read the console messages, figure out what happened, and go to the root cause.
```

Claude will read the program output, identify the ConcurrentModificationException,
set appropriate breakpoints and watchpoints, and use time-travel debugging to
trace back to the concurrent modification that triggered the fail-fast iterator.
