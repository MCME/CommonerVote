package com.mcmiddleearth.commonervote.storage;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs all database work of one server on one dedicated thread, so nothing waits on the database on the main thread,
 * and tells the caller how it went through a {@link CompletableFuture}.
 * <p>
 * A task that throws never leaves its future waiting, and a failure is logged quietly (the 2020 plugin wrote a stack
 * trace for every failed query while the database was down):
 * <ul>
 *   <li>An {@link SQLException} (the database is down or slow) completes the future with a
 *       {@link DatabaseUnavailableException} whose cause it is. Each one leaves a WARNING line; the stack trace is
 *       attached the first time and then only when a minute has passed since the last one that had it.</li>
 *   <li>Any other {@link RuntimeException} is a bug or corrupt data. It completes the future as it is, unwrapped,
 *       and is logged at SEVERE with its stack trace, but only once a minute for each distinct exception message.</li>
 *   <li>An {@link Error} completes the future and is then thrown on, to the executor's thread.</li>
 * </ul>
 * Logging a failure never keeps the future from completing, and the log line is written before the future completes.
 * <p>
 * Calling {@link #supply} after {@link #shutdown} throws the executor's {@code RejectedExecutionException}.
 */
public final class DbExecutor {

    /** How long a failure that was logged in full silences the next one of its kind. */
    private static final long QUIET_MILLIS = Duration.ofSeconds(60).toMillis();

    private final Executor io;
    private final Logger logger;
    private final LongSupplier clock;

    // The two fields below are guarded by this; times are clock readings, in milliseconds.

    /** When the last unavailable-database WARNING with a stack trace was logged; null before the first. */
    private Long lastStackTraceAt;

    /** When each exception message was last logged at SEVERE, for the last minute. */
    private final Map<String, Long> lastBugLoggedAt = new HashMap<>();

    /**
     * @param io     runs the tasks; one thread, or the work of two tasks could overlap
     * @param logger where failures are logged
     * @param clock  the time in milliseconds, for the once-a-minute rule
     */
    public DbExecutor(Executor io, Logger logger, LongSupplier clock) {
        this.io = Objects.requireNonNull(io);
        this.logger = Objects.requireNonNull(logger);
        this.clock = Objects.requireNonNull(clock);
    }

    /** One daemon thread called {@code CommonerVote-DB}, so a stuck query can never keep the JVM from stopping. */
    public static DbExecutor singleThread(Logger logger) {
        ExecutorService thread = Executors.newSingleThreadExecutor(task -> {
            Thread t = new Thread(task, "CommonerVote-DB");
            t.setDaemon(true);
            return t;
        });
        return new DbExecutor(thread, logger, System::currentTimeMillis);
    }

    /**
     * Runs {@code task} on the database thread. The returned future completes there, with the task's result or as
     * described in the class comment; a dependent stage added later may run on the caller's thread.
     */
    public <T> CompletableFuture<T> supply(SqlTask<T> task) {
        Objects.requireNonNull(task);
        CompletableFuture<T> result = new CompletableFuture<>();
        io.execute(() -> {
            try {
                result.complete(task.run());
            } catch (SQLException e) {
                logUnavailable(e);
                result.completeExceptionally(new DatabaseUnavailableException(e));
            } catch (RuntimeException e) {
                logBug(e);
                result.completeExceptionally(e);
            } catch (Error e) {
                result.completeExceptionally(e);
                throw e;
            }
        });
        return result;
    }

    /**
     * Stops taking new work, lets the work already queued finish, and waits up to {@code wait} for that. Returns
     * normally when the time is up. If the thread is interrupted while waiting, it stops waiting and the interrupt
     * flag is set again. Does nothing when the executor given to the constructor is not an {@link ExecutorService}.
     */
    public void shutdown(Duration wait) {
        if (io instanceof ExecutorService service) {
            service.shutdown();
            try {
                service.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void logUnavailable(SQLException e) {
        long now = clock.getAsLong();
        String message = "The vote database is unavailable: " + e.getMessage();
        if (lastStackTraceAt == null || now - lastStackTraceAt >= QUIET_MILLIS) {
            lastStackTraceAt = now;
            logger.log(Level.WARNING, message, e);
        } else {
            logger.warning(message);
        }
    }

    private synchronized void logBug(RuntimeException e) {
        long now = clock.getAsLong();
        // Forget the messages whose minute is over, so the map cannot grow with every distinct message for good.
        lastBugLoggedAt.values().removeIf(loggedAt -> now - loggedAt >= QUIET_MILLIS);
        if (lastBugLoggedAt.putIfAbsent(String.valueOf(e.getMessage()), now) == null) {
            logger.log(Level.SEVERE, "A database task failed unexpectedly: " + e, e);
        }
    }
}
