package run.endive.redline.experimental.api.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import run.endive.runtime.Memory;

/** The open instances importing a native memory or table, which keep it from being freed. */
public final class Borrowers {

    private final Set<Object> borrowers = Collections.newSetFromMap(new IdentityHashMap<>());
    // set by tryClose, undone by reopen when the close it was part of is refused
    private boolean closing;
    private boolean freed;

    /** Records an instance that imports the resource. Fails once its owner closes it. */
    public synchronized void add(Object borrower, String resource) {
        if (closing || freed) {
            throw new IllegalStateException(resource + " has been closed");
        }
        borrowers.add(borrower);
    }

    public synchronized void remove(Object borrower) {
        borrowers.remove(borrower);
    }

    /** Marks the resource as closing unless it is borrowed, in which case nothing changes. */
    public synchronized boolean tryClose() {
        if (freed) {
            return true;
        }
        if (!borrowers.isEmpty()) {
            return false;
        }
        closing = true;
        return true;
    }

    /** Undoes {@link #tryClose()} when the close it was part of is refused. */
    public synchronized void reopen() {
        closing = false;
    }

    /** Called by the resource once it has been freed, which cannot be undone. */
    public synchronized void freed() {
        freed = true;
    }

    public synchronized IllegalStateException stillBorrowed(String resource) {
        return new IllegalStateException(
                resource
                        + " is still imported by "
                        + borrowers.size()
                        + " open instance(s), close them first");
    }

    /** Marks every resource as closing, or none of them, throwing if one is borrowed. */
    public static void tryCloseAll(List<? extends NativeResource> resources) {
        var marked = new ArrayList<Borrowers>();
        for (var resource : resources) {
            var borrowers = resource.borrowers();
            if (!borrowers.tryClose()) {
                for (var done : marked) {
                    done.reopen();
                }
                throw borrowers.stillBorrowed(resource instanceof Memory ? "A memory" : "A table");
            }
            marked.add(borrowers);
        }
    }

    /** Undoes {@link #tryCloseAll}. */
    public static void reopenAll(List<? extends NativeResource> resources) {
        for (var resource : resources) {
            resource.borrowers().reopen();
        }
    }
}
