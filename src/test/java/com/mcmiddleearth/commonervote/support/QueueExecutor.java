package com.mcmiddleearth.commonervote.support;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * An {@link Executor} that only collects what it is given, so a test decides when the work runs. Stands in for the
 * main thread (work posted back to it) and for the database thread.
 * <p>
 * Safe to fill from any thread; {@link #runAll()} is meant for the test thread.
 */
public final class QueueExecutor implements Executor {

    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();

    @Override
    public void execute(Runnable command) {
        queue.add(command);
    }

    /** Runs the collected work in the order it arrived, including any work that running it adds, until none is left. */
    public void runAll() {
        Runnable next;
        while ((next = queue.poll()) != null) {
            next.run();
        }
    }

    /** How many pieces of work are waiting. */
    public int pending() {
        return queue.size();
    }
}
