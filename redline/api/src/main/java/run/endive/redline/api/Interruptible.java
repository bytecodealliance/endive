package run.endive.redline.api;

public interface Interruptible {
    void requestInterrupt();

    void clearInterrupt();
}
