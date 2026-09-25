package run.endive.redline.experimental.api.internal;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Translates {@link Thread#interrupt()} on a thread that is inside native code into a write
 * of {@link CtxBuffer#INTERRUPT_FLAG}, which compiled code polls at function entry and at
 * loop headers.
 *
 * <p>A poller is unavoidable: the JVM offers no callback on interrupt, and a compute loop
 * that never calls a host function reaches no other safepoint. What is avoidable is paying
 * for a fresh {@link Thread} on every call, which is what the runners used to do — a host
 * function calling back into an export paid it again for the nested call.
 *
 * <p>So there is one daemon poller for the whole JVM. It parks while no call is in flight,
 * so an idle process carries no cost, and it wakes on the first registration.
 *
 * <p>Registrations are per call, not per thread: a host function may call into a
 * <em>different</em> machine, and that machine's call is outermost as far as it is
 * concerned, so both have to be watched.
 */
public final class InterruptWatchdog {

    /** How often the poller re-reads the interrupt status of every in-flight caller. */
    private static final long POLL_INTERVAL_NANOS = 1_000_000L;

    private static final Set<Registration> ACTIVE = ConcurrentHashMap.newKeySet();

    private static final AtomicReference<Thread> POLLER = new AtomicReference<>();

    private InterruptWatchdog() {}

    /** Raises the interrupt flag in a machine's context buffer. */
    @FunctionalInterface
    public interface InterruptSink {
        void requestInterrupt();
    }

    /**
     * Starts watching {@code caller} for the duration of one call. The returned handle must
     * be passed to {@link #exit(Registration)} in a {@code finally}.
     */
    public static Registration enter(Thread caller, InterruptSink sink) {
        var registration = new Registration(caller, sink);
        ACTIVE.add(registration);
        LockSupport.unpark(poller());
        return registration;
    }

    /**
     * Stops watching. On return the poller is guaranteed not to be part-way through raising
     * the flag for this registration, so the caller may clear the flag without racing it.
     */
    public static void exit(Registration registration) {
        registration.deactivate();
        ACTIVE.remove(registration);
    }

    /** Visible for testing: how many calls are currently being watched. */
    public static int activeCount() {
        return ACTIVE.size();
    }

    private static Thread poller() {
        Thread existing = POLLER.get();
        if (existing != null) {
            return existing;
        }
        synchronized (InterruptWatchdog.class) {
            existing = POLLER.get();
            if (existing != null) {
                return existing;
            }
            var thread = new Thread(InterruptWatchdog::pollLoop, "endive-redline-interrupt");
            thread.setDaemon(true);
            POLLER.set(thread);
            thread.start();
            return thread;
        }
    }

    private static void pollLoop() {
        while (true) {
            if (ACTIVE.isEmpty()) {
                // A registration that lands between the check and the park still wakes us:
                // unpark leaves a permit behind, so park returns at once.
                LockSupport.park();
                continue;
            }
            for (Registration registration : ACTIVE) {
                registration.poll();
            }
            LockSupport.parkNanos(POLL_INTERVAL_NANOS);
        }
    }

    /** One in-flight call. Identity-based equality, so a thread may hold several. */
    public static final class Registration {

        private final Thread caller;
        private final InterruptSink sink;
        private boolean active = true;

        private Registration(Thread caller, InterruptSink sink) {
            this.caller = caller;
            this.sink = sink;
        }

        // Synchronized against deactivate() so that once exit() returns, no poller thread
        // can still be about to raise the flag. Without it the flag could be set just after
        // the call cleared it, and the next call on that machine would trap for an
        // interrupt nobody requested.
        private void poll() {
            synchronized (this) {
                if (active && caller.isInterrupted()) {
                    sink.requestInterrupt();
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
