package com.mcmiddleearth.commonervote.support;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * An {@link java.util.concurrent.ExecutorService} that runs each task at once, on the caller's thread, and can be shut
 * down like a real one: after {@link #shutdown()} it refuses new tasks with a {@link RejectedExecutionException}, as
 * the plugin's database thread does. A test reads {@link #isShutdown()} to see that the plugin stopped it.
 */
public final class DirectExecutorService extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable task) {
        if (shutdown) {
            throw new RejectedExecutionException("The executor has been shut down");
        }
        task.run();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    /** Shuts down; nothing is ever waiting, so the list is empty. */
    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    /** Every task finished before {@link #execute} returned, so a shut-down executor has terminated. */
    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return shutdown;
    }
}
