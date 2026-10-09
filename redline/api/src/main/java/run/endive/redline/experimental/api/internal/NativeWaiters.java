package run.endive.redline.experimental.api.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import run.endive.runtime.WasmInterruptedException;
import run.endive.wasm.WasmEngineException;

/** memory.atomic.wait and notify for a native memory, as ByteArrayMemory implements them. */
public final class NativeWaiters {

    // Guarded by its own monitor; 0 <= pendingWakeups <= waiterCount
    private static final class WaitState {
        int waiterCount;
        int pendingWakeups;
        // a waiter only consumes a wakeup minted after it registered
        long generation;
    }

    private final Map<Integer, WaitState> waitStates = new ConcurrentHashMap<>();
    private final boolean shared;

    public NativeWaiters(boolean shared) {
        this.shared = shared;
    }

    public Object monitor(int address) {
        return waitStates.computeIfAbsent(address, k -> new WaitState());
    }

    /** Waits while {@code condition} holds: 0 woken, 1 not-equal, 2 timed out. */
    public int waitOn(int address, BooleanSupplier condition, long timeout) {
        if (!shared) {
            throw new WasmEngineException("Attempt to wait on a non-shared memory, not supported.");
        }

        long deadline = (timeout < 0) ? Long.MAX_VALUE : System.nanoTime() + timeout;
        WaitState state = waitStates.computeIfAbsent(address, k -> new WaitState());

        synchronized (state) {
            if (!condition.getAsBoolean()) {
                return 1;
            }

            state.waiterCount++;
            final long arrivalGeneration = state.generation;
            try {
                while (state.pendingWakeups == 0 || state.generation == arrivalGeneration) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return 2;
                    }
                    long millis = Math.max(remaining / 1_000_000L, 0);
                    int nanos = Math.max((int) (remaining % 1_000_000L), 0);
                    try {
                        state.wait(millis, nanos);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new WasmInterruptedException("Thread interrupted");
                    }
                }
                return 0;
            } finally {
                // Only a wakeup minted while this thread waited is its to consume.
                if (state.pendingWakeups > 0 && state.generation != arrivalGeneration) {
                    state.pendingWakeups--;
                }
                state.waiterCount--;
                assert (0 <= state.pendingWakeups);
                assert (state.pendingWakeups <= state.waiterCount);
            }
        }
    }

    /** Wakes up to {@code maxThreads} waiters (all if negative); returns how many. */
    public int notify(int address, int maxThreads) {
        if (!shared) {
            return 0;
        }

        WaitState state = waitStates.get(address);
        if (state == null) {
            return 0;
        }

        synchronized (state) {
            int actualWaiters = state.waiterCount - state.pendingWakeups;
            if (actualWaiters == 0) {
                return 0;
            }
            int toWake = maxThreads < 0 ? actualWaiters : Math.min(actualWaiters, maxThreads);
            // waiters arriving after this notify wait for the next one
            state.pendingWakeups += toWake;
            state.generation++;
            assert (state.pendingWakeups <= state.waiterCount);
            state.notifyAll();
            return toWake;
        }
    }
}
