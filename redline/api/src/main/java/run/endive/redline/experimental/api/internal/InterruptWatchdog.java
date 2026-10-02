package run.endive.redline.experimental.api.internal;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Raises {@link CtxBuffer#INTERRUPT_FLAG} for watched calls whose thread is interrupted. */
public final class InterruptWatchdog {

    private static final Logger LOG = Logger.getLogger(InterruptWatchdog.class.getName());

    private static final long POLL_INTERVAL_NANOS =
            millisProperty("endive.redline.interruptPollMillis", 100);

    // how long the poller waits without calls before it exits
    private static final long IDLE_EXIT_NANOS =
            millisProperty("endive.redline.interruptIdleMillis", 60_000);

    // STATE holds the RUNNING and IDLE flags of the poller plus CALL per watched call
    private static final int RUNNING = 1;

    private static final int IDLE = 2;

    private static final int CALL = 4;

    private static final AtomicInteger STATE = new AtomicInteger();

    private static final Set<Registration> ACTIVE = ConcurrentHashMap.newKeySet();

    private static volatile Thread poller;

    private InterruptWatchdog() {}

    /** Raises the interrupt flag in a machine's context buffer. */
    @FunctionalInterface
    public interface InterruptSink {
        void requestInterrupt();
    }

    /** Watches the current thread until the returned handle is passed to {@link #exit}. */
    public static Registration enter(InterruptSink sink) {
        var registration = new Registration(Thread.currentThread(), sink);
        ACTIVE.add(registration);
        int state = STATE.getAndAdd(CALL);
        try {
            if ((state & RUNNING) == 0) {
                ensurePoller();
            } else if ((state & IDLE) != 0 && (STATE.getAndUpdate(s -> s & ~IDLE) & IDLE) != 0) {
                LockSupport.unpark(poller);
            }
        } catch (RuntimeException | Error e) {
            exit(registration);
            throw e;
        }
        return registration;
    }

    /** Stops watching; once this returns the poller can no longer raise the flag. */
    public static void exit(Registration registration) {
        registration.deactivate();
        ACTIVE.remove(registration);
        STATE.getAndAdd(-CALL);
    }

    private static long millisProperty(String name, long defaultMillis) {
        return TimeUnit.MILLISECONDS.toNanos(Math.max(1, Long.getLong(name, defaultMillis)));
    }

    @SuppressWarnings("ThreadPriorityCheck") // not the priority of whichever caller starts it
    private static void ensurePoller() {
        if ((STATE.getAndUpdate(s -> s | RUNNING) & RUNNING) != 0) {
            return;
        }
        try {
            // no thread locals or class loader from the caller either
            var thread =
                    new Thread(
                            null,
                            InterruptWatchdog::pollLoop,
                            "endive-redline-interrupt",
                            0,
                            false);
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            thread.setPriority(Thread.NORM_PRIORITY);
            poller = thread;
            thread.start();
        } catch (RuntimeException | Error e) {
            STATE.getAndAdd(-RUNNING);
            throw e;
        }
    }

    private static void pollLoop() {
        boolean retired = false;
        try {
            while (true) {
                // an interrupt status would make every park return at once
                Thread.interrupted();
                if (STATE.get() < CALL && idleUntilExit()) {
                    retired = true;
                    return;
                }
                pollAll();
                LockSupport.parkNanos(POLL_INTERVAL_NANOS);
            }
        } finally {
            if (!retired && STATE.updateAndGet(s -> s & ~(RUNNING | IDLE)) >= CALL) {
                // died on an error: hand the watched calls to a new poller
                ensurePoller();
            }
        }
    }

    // parks until a call clears IDLE or the idle time passes; true if the poller should exit
    private static boolean idleUntilExit() {
        if (!STATE.compareAndSet(RUNNING, RUNNING | IDLE)) {
            return false;
        }
        long deadline = System.nanoTime() + IDLE_EXIT_NANOS;
        long left;
        while ((STATE.get() & IDLE) != 0 && (left = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(left);
            Thread.interrupted();
        }
        if (STATE.compareAndSet(RUNNING | IDLE, 0)) {
            return true;
        }
        STATE.getAndUpdate(s -> s & ~IDLE);
        return false;
    }

    private static void pollAll() {
        for (Iterator<Registration> it = ACTIVE.iterator(); it.hasNext(); ) {
            var registration = it.next();
            try {
                registration.poll();
            } catch (RuntimeException | Error e) {
                // a broken sink: stop watching that call rather than every call
                it.remove();
                LOG.log(Level.WARNING, "Stopped watching a call whose interrupt flag failed", e);
            }
        }
    }

    /** One watched call. */
    public static final class Registration {

        private final Thread caller;
        private final InterruptSink sink;
        private boolean active = true;

        private Registration(Thread caller, InterruptSink sink) {
            this.caller = caller;
            this.sink = sink;
        }

        // the lock pairs with deactivate(), so the flag is never raised after exit()
        private void poll() {
            if (caller.isInterrupted()) {
                synchronized (this) {
                    if (active) {
                        sink.requestInterrupt();
                    }
                }
            }
        }

        private void deactivate() {
            synchronized (this) {
                active = false;
            }
        }
    }
}
