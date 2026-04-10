package io.undo.demos;

import java.util.*;

/**
 * Demonstrates ConcurrentModificationException caused by REENTRANT modification, not
 * multi-threading. This is based on a real-world bug pattern in connection pooling code.
 *
 * <p>The twist: All methods are properly synchronized, yet we still get
 * ConcurrentModificationException. Why? Because Java's synchronized is reentrant - the same thread
 * can re-enter a synchronized block it already holds.
 *
 * <p>Demo workflow: 1. Create an exception breakpoint on ConcurrentModificationException 2. Time
 * travel back to where the exception was thrown (in ArrayList iterator) 3. Notice modCount !=
 * expectedModCount 4. Set a watchpoint on modCount 5. Time travel back to where modCount was last
 * modified 6. Discover: it's the SAME THREAD, via a callback chain!
 *
 * <p>The call stack at the crash will show the reentrant path:
 * ConnectionEventListenerSupport.connectionClosed() -> ArrayList.iterator ->
 * PooledConnectionManager.connectionClosed() -> ConnectionEventListenerSupport.remove() ->
 * ArrayList.remove() // modifies modCount -> iterator.next() // BANG! modCount != expectedModCount
 */
public class ReentrantModificationExceptionDemo {

    public static void main(String[] args) {
        ConnectionEventListenerSupport support = new ConnectionEventListenerSupport();

        // Add some normal listeners (logging, metrics, etc.)
        for (int i = 0; i < 5; i++) {
            support.add(new LoggingConnectionListener("Logger-" + i));
        }

        // Add a connection pool manager that unregisters on close - this is the culprit!
        PooledConnectionManager poolManager = new PooledConnectionManager(support);
        support.add(poolManager);

        // Add more listeners after the pool manager
        for (int i = 5; i < 10; i++) {
            support.add(new LoggingConnectionListener("Logger-" + i));
        }

        System.out.println("Connection closing...");
        System.out.println("(All methods are synchronized - this should be safe, right?)");

        // Keep closing connections until we hit the ConcurrentModificationException
        for (int i = 0; ; i++) {
            try {
                // Re-add the troublemaker for subsequent iterations
                if (i > 0) {
                    support.add(new PooledConnectionManager(support));
                }
                support.connectionClosed(new Connection("db-connection-" + i));
            } catch (ConcurrentModificationException e) {
                System.out.println("Caught ConcurrentModificationException on iteration " + i);
                throw e; // Re-throw so debugger can catch it
            }
        }
    }

    /**
     * A listener registry with fully synchronized methods. Looks thread-safe... but isn't safe
     * against reentrant modification!
     */
    static class ConnectionEventListenerSupport {
        private final ArrayList<ConnectionEventListener> listeners = new ArrayList<>();

        public synchronized void add(ConnectionEventListener listener) {
            listeners.add(listener);
        }

        public synchronized void remove(ConnectionEventListener listener) {
            listeners.remove(listener);
        }

        public synchronized void connectionClosed(Connection conn) {
            for (ConnectionEventListener listener : listeners) {
                // This callback can call back into remove()!
                // Since synchronized is reentrant, the same thread can re-enter.
                listener.connectionClosed(conn);
            }
        }
    }

    interface ConnectionEventListener {
        void connectionClosed(Connection conn);
    }

    /** A simple connection wrapper. */
    static class Connection {
        private final String name;

        Connection(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A well-behaved listener that just logs. */
    static class LoggingConnectionListener implements ConnectionEventListener {
        private final String name;

        LoggingConnectionListener(String name) {
            this.name = name;
        }

        @Override
        public void connectionClosed(Connection conn) {
            System.out.println(name + ": connection closed - " + conn);
        }
    }

    /**
     * A connection pool manager that unregisters itself when the connection closes. This is a
     * common pattern (cleanup handlers, one-shot listeners, etc.) and the source of many real-world
     * ConcurrentModificationException bugs.
     */
    static class PooledConnectionManager implements ConnectionEventListener {
        private final ConnectionEventListenerSupport support;

        PooledConnectionManager(ConnectionEventListenerSupport support) {
            this.support = support;
        }

        @Override
        public void connectionClosed(Connection conn) {
            System.out.println(
                    "PooledConnectionManager: connection " + conn + " closed, unregistering...");
            support.remove(this);
        }
    }
}
