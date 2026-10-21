package com.mcmiddleearth.commonervote.storage;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.support.CapturingLogger;
import com.mcmiddleearth.commonervote.support.QueueExecutor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbExecutorTest {

    private final QueueExecutor queue = new QueueExecutor();
    private final CapturingLogger log = CapturingLogger.create();
    private final AtomicLong now = new AtomicLong();
    private final DbExecutor db = new DbExecutor(queue, log, now::get);

    // ---- where the work runs ----

    @Test
    void supply_runs_on_io() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();

        CompletableFuture<Integer> result = db.supply(() -> {
            ran.set(true);
            return 42;
        });

        assertFalse(result.isDone(), "nothing runs until the io executor gets to it");
        assertFalse(ran.get());

        queue.runAll();

        assertTrue(ran.get());
        assertEquals(42, result.get());
        assertEquals(0, log.records().size(), "a success logs nothing");
    }

    @Test
    void runAll_includes_work_queued_while_running() throws Exception {
        AtomicReference<CompletableFuture<String>> inner = new AtomicReference<>();

        CompletableFuture<String> outer = db.supply(() -> {
            inner.set(db.supply(() -> "second"));
            return "first";
        });
        queue.runAll();

        assertEquals("first", outer.get());
        assertEquals("second", inner.get().get());
        assertEquals(0, queue.pending());
    }

    @Test
    void single_thread_runs_everything_on_one_daemon_thread_named_for_the_plugin() throws Exception {
        DbExecutor real = DbExecutor.singleThread(log);
        try {
            Thread first = real.supply(Thread::currentThread).get(10, TimeUnit.SECONDS);
            Thread second = real.supply(Thread::currentThread).get(10, TimeUnit.SECONDS);

            assertEquals("CommonerVote-DB", first.getName());
            assertTrue(first.isDaemon());
            assertSame(first, second);
            assertNotSame(Thread.currentThread(), first);
        } finally {
            real.shutdown(Duration.ofSeconds(10));
        }
    }

    // ---- a database that is down ----

    @Test
    void failure_policy() throws Exception {
        SQLException first = new SQLException("Communications link failure");
        SQLException second = new SQLException("Communications link failure");
        SQLException third = new SQLException("Communications link failure");

        CompletableFuture<String> one = failAt(0, first);
        CompletableFuture<String> two = failAt(10_000, second);
        CompletableFuture<String> three = failAt(61_000, third);

        assertUnavailable(one, first);
        assertUnavailable(two, second);
        assertUnavailable(three, third);

        List<LogRecord> records = log.records();
        assertEquals(3, records.size(), "every failure leaves one line");
        for (LogRecord record : records) {
            assertEquals(Level.WARNING, record.getLevel());
        }
        assertSame(first, records.get(0).getThrown(), "the first failure comes with its stack trace");
        assertNull(records.get(1).getThrown(), "10 s later it is a plain line");
        assertSame(third, records.get(2).getThrown(), "61 s after the trace it comes with a stack trace again");
    }

    @Test
    void a_stack_trace_returns_after_exactly_sixty_seconds() {
        SQLException first = new SQLException("down");
        SQLException justBefore = new SQLException("down");
        SQLException onTheMinute = new SQLException("down");

        failAt(5_000, first);
        failAt(64_999, justBefore);
        failAt(65_000, onTheMinute);

        List<LogRecord> records = log.records();
        assertEquals(3, records.size());
        assertSame(first, records.get(0).getThrown());
        assertNull(records.get(1).getThrown());
        assertSame(onTheMinute, records.get(2).getThrown());
    }

    @Test
    void the_line_without_a_stack_trace_still_says_what_failed() {
        failAt(0, new SQLException("Communications link failure"));
        failAt(1, new SQLException("Connection refused"));

        LogRecord plain = log.records().get(1);
        assertTrue(plain.getMessage().contains("Connection refused"), plain.getMessage());
    }

    // ---- a bug in the work ----

    @Test
    void a_bug_is_passed_through_unwrapped_and_logged_severe() throws Exception {
        IllegalStateException bug = new IllegalStateException("corrupt row");

        CompletableFuture<String> result = bugAt(0, bug);

        ExecutionException failure = assertThrows(ExecutionException.class, result::get);
        assertSame(bug, failure.getCause(), "the caller sees the bug itself, not a 'database unavailable'");
        List<LogRecord> records = log.records();
        assertEquals(1, records.size());
        assertEquals(Level.SEVERE, records.get(0).getLevel());
        assertSame(bug, records.get(0).getThrown());
    }

    @Test
    void the_same_bug_message_is_logged_once_a_minute() throws Exception {
        IllegalStateException loggedAtStart = new IllegalStateException("corrupt row");
        IllegalStateException repeatedSoon = new IllegalStateException("corrupt row");
        IllegalStateException other = new IllegalStateException("another problem");
        IllegalStateException repeatedAgain = new IllegalStateException("corrupt row");
        IllegalStateException afterAMinute = new IllegalStateException("corrupt row");

        CompletableFuture<String> a = bugAt(0, loggedAtStart);
        CompletableFuture<String> b = bugAt(10_000, repeatedSoon);
        CompletableFuture<String> c = bugAt(10_000, other);
        CompletableFuture<String> d = bugAt(59_999, repeatedAgain);
        CompletableFuture<String> e = bugAt(60_000, afterAMinute);

        for (CompletableFuture<String> f : List.of(a, b, c, d, e)) {
            assertTrue(f.isCompletedExceptionally(), "a suppressed log line never suppresses the failure itself");
        }
        List<LogRecord> records = log.records();
        assertEquals(3, records.size());
        assertSame(loggedAtStart, records.get(0).getThrown());
        assertSame(other, records.get(1).getThrown());
        assertSame(afterAMinute, records.get(2).getThrown());
        for (LogRecord record : records) {
            assertEquals(Level.SEVERE, record.getLevel());
        }
    }

    @Test
    void bugs_and_an_unreachable_database_are_throttled_separately() {
        IllegalStateException bug = new IllegalStateException("corrupt row");
        SQLException down = new SQLException("down");

        bugAt(0, bug);
        failAt(1_000, down);

        List<LogRecord> records = log.records();
        assertEquals(2, records.size());
        assertEquals(Level.SEVERE, records.get(0).getLevel());
        assertEquals(Level.WARNING, records.get(1).getLevel());
        assertSame(down, records.get(1).getThrown(), "the bug did not use up the stack trace of the first outage");
    }

    @Test
    void an_error_fails_the_future_and_goes_on_to_the_executor() {
        StackOverflowError error = new StackOverflowError("deep");
        CompletableFuture<String> result = db.supply(() -> {
            throw error;
        });

        assertSame(error, assertThrows(StackOverflowError.class, queue::runAll));

        assertTrue(result.isCompletedExceptionally(), "the caller is not left waiting");
        assertEquals(0, log.records().size());
    }

    // ---- shutdown ----

    @Test
    void shutdown_waits_for_the_work_in_flight() throws Exception {
        DbExecutor real = DbExecutor.singleThread(log);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<String> slow = real.supply(() -> {
            started.countDown();
            await(release);
            return "done";
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.start();

        real.shutdown(Duration.ofSeconds(10));

        assertTrue(slow.isDone(), "shutdown returned only after the running task finished");
        assertEquals("done", slow.get());
        releaser.join();
    }

    @Test
    void shutdown_gives_up_waiting_after_the_given_time() throws Exception {
        DbExecutor real = DbExecutor.singleThread(log);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<String> stuck = real.supply(() -> {
            started.countDown();
            await(release);
            return "done";
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        try {
            assertDoesNotThrow(() -> real.shutdown(Duration.ofMillis(50)));
            assertFalse(stuck.isDone());
        } finally {
            release.countDown();
        }
        assertEquals("done", stuck.get(10, TimeUnit.SECONDS));
    }

    @Test
    void an_interrupted_shutdown_does_not_throw_and_keeps_the_interrupt() throws Exception {
        DbExecutor real = DbExecutor.singleThread(log);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<String> running = real.supply(() -> {
            started.countDown();
            await(release);
            return "done";
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        Thread.currentThread().interrupt();
        try {
            assertDoesNotThrow(() -> real.shutdown(Duration.ofSeconds(10)));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is restored");
        } finally {
            Thread.interrupted(); // clear it for the other tests on this thread
            release.countDown();
        }
        assertEquals("done", running.get(10, TimeUnit.SECONDS));
    }

    @Test
    void shutdown_does_nothing_when_the_io_executor_is_not_a_service() {
        assertDoesNotThrow(() -> db.shutdown(Duration.ofSeconds(1)));
    }

    // ---- helpers ----

    /** Fails a task with {@code failure} when the clock reads {@code time}, and returns its future once it ran. */
    private CompletableFuture<String> failAt(long time, SQLException failure) {
        now.set(time);
        CompletableFuture<String> result = db.supply(() -> {
            throw failure;
        });
        queue.runAll();
        return result;
    }

    private CompletableFuture<String> bugAt(long time, RuntimeException bug) {
        now.set(time);
        CompletableFuture<String> result = db.supply(() -> {
            throw bug;
        });
        queue.runAll();
        return result;
    }

    private static void assertUnavailable(CompletableFuture<?> result, SQLException cause) {
        ExecutionException failure = assertThrows(ExecutionException.class, result::get);
        assertInstanceOf(DatabaseUnavailableException.class, failure.getCause());
        assertSame(cause, failure.getCause().getCause());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the test to release the task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
