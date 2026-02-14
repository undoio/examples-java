# java-demos

Java demo programs for debugging and profiling demonstrations.

## Prerequisites

- Java 17+
- Maven 3.6+

## Build

```bash
mvn compile
```

## Demos

| Demo | What it demonstrates | How to run |
|------|---------------------|------------|
| ConcurrentModificationExceptionDemo | Multi-threaded modification of a shared ArrayList causes ConcurrentModificationException | `mvn compile exec:java -Dexec.mainClass=io.undo.demos.ConcurrentModificationExceptionDemo` |
| ReentrantModificationExceptionDemo | Single-threaded reentrant modification via synchronized callbacks - all methods are synchronized yet it still throws ConcurrentModificationException | `mvn compile exec:java -Dexec.mainClass=io.undo.demos.ReentrantModificationExceptionDemo` |
| WaitNotifyDemo | Producer silently stops calling notify(), leaving its consumer stuck in wait() forever. Hard to debug because the stuck thread's stack just shows wait() with no clue why | `mvn compile exec:java -Dexec.mainClass=io.undo.demos.WaitNotifyDemo` |

## Debugging with Claude Code

You can use [Claude Code](https://docs.anthropic.com/en/docs/claude-code) to interactively debug
Undo recordings via an MCP server. Claude can read console output, navigate the recording,
set breakpoints and watchpoints, and travel backwards in time to find root causes.

### 1. Convert your license key

Claude Code requires a PEM-format key file. If you have a binary `.key` file,
convert it first:

```bash
./scripts/convert_key.sh /path/to/binary.key /path/to/license.pem
```

### 2. Add the MCP server to Claude Code

Register the recording as an MCP server so Claude can debug it:

```bash
claude mcp add ConcurrentModificationExceptionDemo \
    -e BRIDGELOG=ConcurrentModificationExceptionDemo-bridge.log \
    -- $LR4J_HOME/lr4j-replay-1.0/lr4j/lr4j_mcp \
    --input ConcurrentModificationExceptionDemo.undo \
    --key /path/to/license.pem
```

Replace `/path/to/license.pem` with the path to your converted PEM key file,
and set `$LR4J_HOME` to the directory containing your lr4j installation.

### 3. Start debugging

Launch `claude` and ask:

> Read the console messages, figure out what happened, and go to the root cause.

Claude will read the program output, identify the exception, set appropriate
breakpoints and watchpoints, and use time-travel debugging to trace back to the
root cause of the bug.

## Scripting Demo: WaitNotifyDemo

The `scripts/` directory contains Python scripts that use Undo's scripting API to
automatically diagnose the WaitNotifyDemo bug from a recording.

### Workflow

1. **Record** the WaitNotifyDemo with Undo
2. **Run a script** against the recording to automatically find the root cause

### Running

```bash
cd /path/to/lr4j-replay-1.0/lr4j
export PYTHONPATH=demos/python

python3 /path/to/java-demos/scripts/wait_notify_simple.py \
    ./lr4j_mcp \
    /path/to/recording.undo \
    /path/to/license.key
```

### Adapting for Your Code

The scripts reference WaitNotifyDemo class names and line numbers. To adapt for a different application:

| What to find | What to change |
|--------------|----------------|
| `'Consumer-'` | Your thread name prefix |
| `WaitNotifyDemo$Consumer` | Your fully-qualified consumer class |
| `WaitNotifyDemo$Producer` | Your fully-qualified producer class |
| `PRODUCER_SKIP_LINE = 239` | Line number where the bug occurs |
| `fieldName='consumer'` | Field name linking producer to consumer |
