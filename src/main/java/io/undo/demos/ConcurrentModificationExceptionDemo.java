package io.undo.demos;

import java.util.*;
import java.util.concurrent.locks.LockSupport;

/**
 * Demonstrates ConcurrentModificationException by iterating over a shared ArrayList while other
 * threads modify it concurrently.
 */
public class ConcurrentModificationExceptionDemo {
    static List<String> list = new ArrayList<>(Collections.nCopies(20, "item"));

    public static void main(String[] args) {
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
                System.out.println("caught " + e + " with i = " + i);
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
