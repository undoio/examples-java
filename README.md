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
