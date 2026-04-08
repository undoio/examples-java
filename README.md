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
| WheresWally | Hides "Wally" among 5,000 generated names, writes to stdout and a temp file. Simple target for demonstrating precise execution navigation and syscall analysis | `mvn compile exec:java -Dexec.mainClass=io.undo.demos.WheresWally` |
| MixedHotspot | Four distinct CPU workloads (prime sieve, SHA-256, string/regex, array sorting) that produce different signatures in Java and native profilers | `mvn compile exec:java -Dexec.mainClass=io.undo.demos.MixedHotspot` |
| [Vega](vega/) | Multi-threaded options analytics engine — simulates exchange feeds, computes implied volatility via Black-Scholes, and maintains live vol surfaces. Contains an intentional bisection convergence bug for time-travel debugging | `cd vega && mvn exec:java` |

## Debugging with Claude Code

You can use [Claude Code](https://docs.anthropic.com/en/docs/claude-code) to interactively debug
Undo recordings via an MCP server. Claude can read console output, navigate the recording,
set breakpoints and watchpoints, and travel backwards in time to find root causes.

### Setup

**1. Convert your license key**

Claude Code requires a PEM-format key file. If you have a binary `.key` file,
convert it first:

```bash
./scripts/convert_key.sh /path/to/binary.key /path/to/license.pem
```

**2. Record the demo**

```bash
java -javaagent:${LR4J_HOME}/lr4j-record-1.0/lr4j_agent_x64.so=save_on=exit \
    -cp target/classes io.undo.demos.DemoName
```

**3. Register the recording as an MCP server**

```bash
claude mcp add DemoName \
    -e BRIDGELOG=DemoName-bridge.log \
    -- $LR4J_HOME/lr4j-replay-1.0/lr4j/lr4j_mcp \
    --input DemoName.undo \
    --key /path/to/license.pem
```

Replace `/path/to/license.pem` with the path to your converted PEM key file,
and set `$LR4J_HOME` to the directory containing your lr4j installation.

**4. Launch `claude` and use the suggested prompts below.**

### AI Demo: Bug Diagnosis (ConcurrentModificationException, ReentrantModificationException, WaitNotifyDemo)

These demos contain bugs that Claude can diagnose end-to-end from a recording.
The prompt is deliberately open-ended — it does not reveal the bug or tell
Claude where to look.

> Read the console messages, figure out what happened, and go to the root cause.

Claude will read the program output, identify the exception or stuck thread,
set appropriate breakpoints and watchpoints, and use time-travel debugging to
trace back to the root cause.

### AI Demo: Profiling (MixedHotspot)

MixedHotspot has four distinct workloads (prime sieve, SHA-256 crypto,
string/regex churn, array sorting) that produce different signatures in Java
and native profilers. Use these prompts in sequence:

**Java-level profiling:**

> Sample the stack traces at a minimum of 30 points across the execution range
> and show me which methods in MixedHotspot.java are consuming the most time,
> along with what they're calling into.

**Native-level profiling:**

> Now do the same thing but for native stack traces using goto_native_bbcount.

Claude will statistically sample the execution, build a profile of where time
is spent, and show how Java methods map to native code (JIT-compiled loops,
crypto internals, GC activity, etc).

### AI Demo: Low-Level Analysis (WheresWally)

WheresWally is a simple program that hides a known string in a stream of
output. These prompts demonstrate precise execution navigation and syscall-level
analysis.

**Binary search to exact execution point:**

> Using binary chop, go to the precise native bbcount where the string "Here is
> Wally on stdout" was created and print the complete native stack there. Use
> the python api if possible.

**Syscall-level write analysis:**

> Ok now I'd like to see ALL the write calls with length greater than 4. Please
> first find all the write events in the undo log noting the bbcount and write
> length. Then for each one go to the native bbcount and read the buffer
> written. Report as string for each together with fd. Output as a complete
> table.

Claude will use the Undo event log and memory inspection tools to find and
decode every write syscall in the recording.

### AI Demo: Complex System Debugging (Vega)

See the [Vega README](vega/README.md) for a dedicated debugging exercise
involving a multi-threaded options analytics engine with an intentional
Black-Scholes bug. The prompt is:

> You have access to a recording of a Java application called Vega — a
> real-time options analytics engine. Something is going wrong at runtime but
> we're not sure what. Start by reading the console output to understand what
> the application is doing and look for anything suspicious. Then use the
> time-travel debugging tools to work backwards from any anomalies you find to
> identify the root cause in the source code.

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
