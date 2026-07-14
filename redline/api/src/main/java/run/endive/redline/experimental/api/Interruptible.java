package run.endive.redline.experimental.api;

public interface Interruptible {
    void requestInterrupt();

    void clearInterrupt();
}
