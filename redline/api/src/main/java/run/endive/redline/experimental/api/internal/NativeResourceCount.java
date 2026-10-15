package run.endive.redline.experimental.api.internal;

import java.util.concurrent.atomic.AtomicInteger;

/** Live native allocations (machines, memories, tables), so tests can check nothing leaks. */
public final class NativeResourceCount {

    private static final AtomicInteger LIVE = new AtomicInteger();

    private NativeResourceCount() {}

    public static void allocated() {
        LIVE.incrementAndGet();
    }

    public static void freed() {
        LIVE.decrementAndGet();
    }

    public static int live() {
        return LIVE.get();
    }
}
