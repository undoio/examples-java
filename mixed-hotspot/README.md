# MixedHotspot Demo

Four distinct CPU workloads that produce different signatures in Java and
native profilers:

```
Method           Workload                          Profiler signature
---------------  --------------------------------  ------------------------------------
sievePrimes()    Sieve of Eratosthenes up to 500K  Pure arithmetic, JIT-compiled loop
cryptoHash()     200 rounds of SHA-256             Java security framework + native crypto
stringChurn()    CSV build/split/sort/join         String/regex allocation, GC pressure
sortArrays()     Sort 100K-element int array       Comparison-heavy (DualPivotQuicksort)
```

Each iteration calls all four methods. Default is 2000 iterations.

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
claude mcp add MixedHotspot \
    -e BRIDGELOG=MixedHotspot-bridge.log \
    -- $LR4J_HOME/bin/lr4j_mcp \
    --input MixedHotspot.undo \
    --key /path/to/license.pem
```

**2. Launch `claude` from this directory and try these prompts in sequence:**

**Java-level profiling:**

```
Sample the stack traces at a minimum of 30 points across the execution range
and show me which methods in MixedHotspot.java are consuming the most time,
along with what they're calling into.
```

**Native-level profiling:**

```
Now do the same thing but for native stack traces using goto_native_bbcount.
```

Claude will statistically sample the execution, build a profile of where time
is spent, and show how Java methods map to native code (JIT-compiled loops,
crypto internals, GC activity, etc).

## Scripting

The `scripts/` directory contains Python scripts that use Undo's scripting API
to perform Java-level and native-level profiling programmatically.
