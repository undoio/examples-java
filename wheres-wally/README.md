# WheresWally Demo

Hides "Wally" among 5,000 generated names, writes to stdout and a temp file.
A simple target for demonstrating precise execution navigation and
syscall-level analysis with time-travel debugging.

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
claude mcp add WheresWally \
    -e BRIDGELOG=WheresWally-bridge.log \
    -- $LR4J_HOME/bin/lr4j_mcp \
    --input WheresWally.undo \
    --key /path/to/license.pem
```

**2. Launch `claude` from this directory and try these prompts:**

**Binary search to exact execution point:**

```
Using binary chop, go to the precise native bbcount where the string "Here is
Wally on stdout" was created and print the complete native stack there. Use
the python api if possible.
```

**Syscall-level write analysis:**

```
Ok now I'd like to see ALL the write calls with length greater than 4. Please
first find all the write events in the undo log noting the bbcount and write
length. Then for each one go to the native bbcount and read the buffer
written. Report as string for each together with fd. Output as a complete
table.
```

Claude will use the Undo event log and memory inspection tools to find and
decode every write syscall in the recording.

## Scripting

The `scripts/` directory contains Python scripts that use Undo's scripting API
to perform the binary chop and write event analysis programmatically.
