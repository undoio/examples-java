package io.undo.demos;

import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

/**
 * Demonstrates ConcurrentModificationException by iterating over a shared ArrayList while other
 * threads modify it concurrently.
 */
public class ConcurrentModificationExceptionDemo {
    static final Logger logger = Logger.getLogger(ConcurrentModificationExceptionDemo.class.getName());
    static List<String> list = new ArrayList<>(Collections.nCopies(20, "item"));

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT.%1$tL %4$s: %5$s%6$s%n");
        new Thread(
                        () -> {
                            while (true) {
                                LockSupport.parkNanos(10_000_000); // sleep 10ms
                                new Thread(new MutatorThread(), "MutatorThread").start();
                            }
                        },
                        "BackgroundThread")
                .start();

        for (int i = 0; ; i++) {
            try {
                for (String s : list) {
                    if (s == null) throw new AssertionError("unexpected null");
                }
            } catch (ConcurrentModificationException | NoSuchElementException e) {
                logger.severe("caught " + e + " with i = " + i);
                System.exit(0);
            }
        }
    }

    /** Modifies the shared list by adding then removing an element. */
    static class MutatorThread implements Runnable {
        public void run() {
            list.add("x");
            list.remove(list.size() - 1);
        }
    }
}
