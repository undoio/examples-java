# ReentrantModificationException Demo

Single-threaded reentrant modification via synchronized callbacks. All methods
are properly synchronized, yet the program still throws
ConcurrentModificationException.

The twist: Java's `synchronized` is reentrant — the same thread can re-enter a
synchronized block it already holds. A connection pool manager unregisters
itself during a callback while the caller is iterating over the listener list.
This is based on a real-world bug pattern in connection pooling code.

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
claude mcp add ReentrantModification \
    -e BRIDGELOG=ReentrantModification-bridge.log \
    -- $LR4J_HOME/bin/lr4j_mcp \
    --input ReentrantModificationExceptionDemo.undo \
    --key /path/to/license.pem
```

**2. Launch `claude` from this directory and ask:**

```
Read the console messages, figure out what happened, and go to the root cause.
```

Claude will identify the ConcurrentModificationException, discover that it's
single-threaded (not a concurrency bug), and trace the reentrant call path:
`connectionClosed()` iterates listeners → `PooledConnectionManager.connectionClosed()`
calls `remove()` → the iterator detects `modCount != expectedModCount`.

### Debugging walkthrough

1. Create an exception breakpoint on ConcurrentModificationException
2. Time travel back to where the exception was thrown (in ArrayList iterator)
3. Notice `modCount != expectedModCount`
4. Set a watchpoint on `modCount`
5. Time travel back to where `modCount` was last modified
6. Discover: it's the same thread, via a callback chain
