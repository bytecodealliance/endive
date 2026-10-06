package run.endive.runtime;

/**
 * An optional per-thread execution budget.
 *
 * <p>A host that runs untrusted Wasm needs to stop a program that never finishes. Thread
 * interruption already does that when the host decides to act, but it cannot express "this program
 * may run for at most N units of work", which is what a scheduler sharing one machine between many
 * programs needs.
 *
 * <p>Fuel is consumed wherever the engine already checks for interruption: backward jumps and
 * calls. That is not per-instruction accounting, and it is not meant to be — it is enough to bound
 * a loop that would otherwise never end, and it costs nothing at the points in between.
 *
 * <p>Fuel bounds work, not memory. It will stop a module that computes forever; it will not stop
 * one that allocates heavily.
 *
 * <p>Fuel is per thread because interruption is per thread, so a host that runs one program per
 * thread gets one budget per program with no further bookkeeping:
 *
 * <pre>{@code
 * Fuel.set(1_000_000);
 * try {
 *     instance.export("_start").apply();
 * } catch (WasmOutOfFuelException e) {
 *     // the program outstayed its budget
 * } finally {
 *     Fuel.clear();
 * }
 * }</pre>
 *
 * <p>Metering is off unless a host turns it on, and while it is off the only cost is one static
 * read at points that already do more work than that.
 */
public final class Fuel {

    /** The value {@link #remaining()} reports when this thread is not metered. */
    public static final long UNLIMITED = -1;

    /**
     * False until some thread first sets a budget. Kept as a fast path so hosts that never meter
     * do not pay for a thread-local lookup on every backward jump.
     */
    private static volatile boolean enabled;

    private static final ThreadLocal<long[]> REMAINING =
            ThreadLocal.withInitial(() -> new long[] {UNLIMITED});

    private Fuel() {}

    /**
     * Consumes one unit on the current thread, throwing {@link WasmOutOfFuelException} once the
     * budget is exhausted. Called by the engine; hosts do not need to call it.
     */
    public static void consume() {
        if (!enabled) {
            return;
        }
        long[] cell = REMAINING.get();
        long remaining = cell[0];
        if (remaining == UNLIMITED) {
            return;
        }
        if (remaining == 0) {
            throw new WasmOutOfFuelException("Out of fuel");
        }
        cell[0] = remaining - 1;
    }

    /** Gives the current thread a budget of {@code units}, replacing any budget it already had. */
    public static void set(long units) {
        if (units < 0) {
            throw new IllegalArgumentException("Fuel budget must not be negative");
        }
        enabled = true;
        REMAINING.get()[0] = units;
    }

    /** Stops metering the current thread. */
    public static void clear() {
        REMAINING.get()[0] = UNLIMITED;
    }

    /** What is left on the current thread, or {@link #UNLIMITED} when it is not metered. */
    public static long remaining() {
        return REMAINING.get()[0];
    }
}
