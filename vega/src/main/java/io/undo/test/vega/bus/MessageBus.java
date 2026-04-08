package io.undo.test.vega.bus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple in-process pub/sub message bus. Publishers push messages to a shared queue; a dispatch
 * thread fans them out to all registered subscribers.
 *
 * <p>Typical of the internal messaging patterns found in trading systems that need to decouple feed
 * handlers from processing engines.
 *
 * @param <T> the message type
 */
public class MessageBus<T> {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBus.class);

    public interface Subscriber<T> {
        void onMessage(T message);
    }

    private final String name;
    private final LinkedBlockingQueue<T> queue;
    private final List<Subscriber<T>> subscribers;
    private final AtomicBoolean running;
    private final AtomicLong publishedCount;
    private final AtomicLong dispatchedCount;
    private final AtomicLong droppedCount;
    private Thread dispatchThread;

    public MessageBus(String name, int capacity) {
        this.name = name;
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.subscribers = new CopyOnWriteArrayList<>();
        this.running = new AtomicBoolean(false);
        this.publishedCount = new AtomicLong(0);
        this.dispatchedCount = new AtomicLong(0);
        this.droppedCount = new AtomicLong(0);
    }

    public void subscribe(Subscriber<T> subscriber) {
        subscribers.add(subscriber);
        LOG.info("Bus [{}]: subscriber added (total: {})", name, subscribers.size());
    }

    public boolean publish(T message) {
        boolean accepted = queue.offer(message);
        if (accepted) {
            publishedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
            if (droppedCount.get() % 1000 == 1) {
                LOG.warn(
                        "Bus [{}]: queue full, message dropped (total dropped: {})",
                        name,
                        droppedCount.get());
            }
        }
        return accepted;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            dispatchThread = new Thread(this::dispatchLoop, "bus-" + name + "-dispatch");
            dispatchThread.setDaemon(true);
            dispatchThread.start();
            LOG.info("Bus [{}]: started with capacity {}", name, queue.remainingCapacity());
        }
    }

    public void stop() {
        running.set(false);
        if (dispatchThread != null) {
            dispatchThread.interrupt();
            try {
                dispatchThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info(
                "Bus [{}]: stopped. Published: {}, Dispatched: {}, Dropped: {}",
                name,
                publishedCount.get(),
                dispatchedCount.get(),
                droppedCount.get());
    }

    private void dispatchLoop() {
        LOG.info("Bus [{}]: dispatch thread running", name);
        while (running.get()) {
            try {
                T message = queue.poll(100, TimeUnit.MILLISECONDS);
                if (message != null) {
                    for (Subscriber<T> sub : subscribers) {
                        try {
                            sub.onMessage(message);
                            dispatchedCount.incrementAndGet();
                        } catch (Exception e) {
                            LOG.error("Bus [{}]: subscriber threw exception", name, e);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.info("Bus [{}]: dispatch thread exiting", name);
    }

    public long getPublishedCount() {
        return publishedCount.get();
    }

    public long getDispatchedCount() {
        return dispatchedCount.get();
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public int getQueueDepth() {
        return queue.size();
    }

    public String getName() {
        return name;
    }
}
