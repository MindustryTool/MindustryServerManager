package graph.runtime;

public interface MainThreadDispatcher {

    boolean isMainThread();

    void post(Runnable runnable);
}
