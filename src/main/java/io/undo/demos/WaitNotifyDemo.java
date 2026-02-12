package io.undo.demos;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates a wait/notify bug: a producer silently stops calling notify(),
 * leaving its consumer stuck in wait() forever.
 *
 * <p>Design: Each producer/consumer pair uses lockstep synchronization.
 * The producer sends work via notify(), the consumer processes it and acks.
 * Halfway through, one producer stops notifying - its consumer hangs.
 *
 * <p>This is hard to debug conventionally because:
 * - The stuck thread's stack just shows wait() - no clue WHY
 * - The producer that skipped notify has already finished
 * - With 10 pairs running concurrently, finding which producer caused it is a needle-in-a-haystack
 *
 * <p>Usage: Record this program with Undo, then use the companion Python scripts
 * to automatically find the stuck thread and the exact moment notify was skipped.
 */
public class WaitNotifyDemo {

    private static final int NUM_PAIRS = 10;
    private static final int ITERATIONS_PER_PRODUCER = 500;
    private static final int TOTAL_ITERATIONS = NUM_PAIRS * ITERATIONS_PER_PRODUCER;
    private static final int SKIP_NOTIFY_AT_COUNT = TOTAL_ITERATIONS / 2;

    private static final AtomicInteger globalCount = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        String logPath = System.getProperty("waitnotify.logfile", "wait_notify_demo.log");
        System.setOut(new PrintStream(new FileOutputStream(logPath)));

        System.out.println("Starting WaitNotifyDemo (lockstep version)");
        System.out.println("Configuration: " + NUM_PAIRS + " producer/consumer pairs");
        System.out.println(
                "Total iterations: "
                        + TOTAL_ITERATIONS
                        + ", skip notify at count: "
                        + SKIP_NOTIFY_AT_COUNT);
        new WaitNotifyDemo().run();
    }

    public void run() throws InterruptedException {
        CountDownLatch consumersReady = new CountDownLatch(NUM_PAIRS);
        CountDownLatch startProducers = new CountDownLatch(1);

        Consumer[] consumers = new Consumer[NUM_PAIRS];
        Producer[] producers = new Producer[NUM_PAIRS];
        Thread[] consumerThreads = new Thread[NUM_PAIRS];
        Thread[] producerThreads = new Thread[NUM_PAIRS];

        for (int i = 0; i < NUM_PAIRS; i++) {
            consumers[i] = new Consumer("Consumer-" + i, consumersReady);
            producers[i] =
                    new Producer(
                            "Producer-" + i, consumers[i], ITERATIONS_PER_PRODUCER, startProducers);
        }

        for (int i = 0; i < NUM_PAIRS; i++) {
            consumerThreads[i] = new Thread(consumers[i], consumers[i].id);
            consumerThreads[i].setDaemon(true);
            consumerThreads[i].start();
        }

        consumersReady.await();

        for (int i = 0; i < NUM_PAIRS; i++) {
            producerThreads[i] = new Thread(producers[i], producers[i].id);
            producerThreads[i].start();
        }

        startProducers.countDown();

        for (Thread t : producerThreads) {
            t.join();
        }

        System.out.println("\nAll producers finished");
        System.out.println("Global count: " + globalCount.get());

        for (Producer p : producers) {
            System.out.println(
                    p.id
                            + ": iterations="
                            + p.iterationCount
                            + ", notified="
                            + p.notifyCount
                            + ", skipped="
                            + p.skippedCount
                            + ", skipFlag="
                            + p.skipFlag);
        }

        System.out.println("\nConsumer states:");
        for (int i = 0; i < NUM_PAIRS; i++) {
            Consumer c = consumers[i];
            System.out.println(
                    c.id
                            + ": wakeCount="
                            + c.wakeCount
                            + ", state="
                            + consumerThreads[i].getState()
                            + ", skipFlag="
                            + c.skipFlag);
        }

        boolean foundStuck = false;
        for (int i = 0; i < NUM_PAIRS; i++) {
            Consumer c = consumers[i];
            Thread t = consumerThreads[i];
            if (c.skipFlag && t.getState() == Thread.State.WAITING) {
                System.out.println("\nBUG REPRODUCED: " + c.id + " is stuck!");
                System.out.println("  Consumer skipFlag: " + c.skipFlag);
                foundStuck = true;
            }
        }

        if (!foundStuck) {
            System.out.println("\nNo stuck consumers detected");
        }

        System.out.println("\nDemo completed");
    }

    /**
     * Consumer waits for producer to send work, then acknowledges.
     * Lockstep: wait -> process -> ack -> wait -> ...
     */
    static class Consumer implements Runnable {
        final String id;
        final CountDownLatch ready;
        volatile boolean skipFlag = false;
        private boolean hasWork = false;
        int wakeCount = 0;

        Consumer(String id, CountDownLatch ready) {
            this.id = id;
            this.ready = ready;
        }

        synchronized void sendWork() {
            hasWork = true;
            this.notify();
        }

        synchronized void waitForAck() {
            while (hasWork) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void run() {
            System.out.println(id + " started");
            synchronized (this) {
                ready.countDown();
            }

            while (true) {
                synchronized (this) {
                    while (!hasWork) {
                        try {
                            this.wait(); // Consumer waits for work here
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    wakeCount++;
                    hasWork = false;
                    this.notify();
                }
            }
        }
    }

    /**
     * Producer sends work to consumer and waits for ack.
     * Lockstep: send -> wait for ack -> send -> ...
     * Once the global count hits the threshold, one producer stops notifying.
     */
    static class Producer implements Runnable {
        final String id;
        private final Consumer consumer;
        private final int iterations;
        private final CountDownLatch startGate;
        boolean skipFlag = false;
        int iterationCount = 0;
        int notifyCount = 0;
        int skippedCount = 0;

        Producer(String id, Consumer consumer, int iterations, CountDownLatch startGate) {
            this.id = id;
            this.consumer = consumer;
            this.iterations = iterations;
            this.startGate = startGate;
        }

        @Override
        public void run() {
            System.out.println(id + " started, assigned to " + consumer.id);

            try {
                startGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            for (int i = 0; i < iterations; i++) {
                int count = globalCount.incrementAndGet();
                iterationCount++;

                if (count == SKIP_NOTIFY_AT_COUNT) {
                    skipFlag = true;
                    consumer.skipFlag = true;
                    System.out.println(
                            id
                                    + " hit skip count "
                                    + count
                                    + ", will no longer notify "
                                    + consumer.id);
                }

                if (!skipFlag) {
                    consumer.sendWork();
                    notifyCount++;
                    consumer.waitForAck();
                } else {
                    skippedCount++; // Producer skips notify here - consumer stays stuck
                }
            }
            System.out.println(id + " finished");
        }
    }
}
