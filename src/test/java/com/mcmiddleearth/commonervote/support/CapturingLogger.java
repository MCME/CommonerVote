package com.mcmiddleearth.commonervote.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * A {@link Logger} that keeps every record it is given, at every level, instead of printing it. It has no parent, so
 * nothing reaches the console or the test log.
 */
public final class CapturingLogger extends Logger {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    private CapturingLogger() {
        super("CapturingLogger", null);
        setUseParentHandlers(false);
        setLevel(Level.ALL);
        addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    public static CapturingLogger create() {
        return new CapturingLogger();
    }

    /** Everything logged so far, oldest first. A snapshot: later records do not show up in it. */
    public List<LogRecord> records() {
        return List.copyOf(records);
    }

    /** The records logged so far at exactly {@code level}, oldest first. */
    public List<LogRecord> records(Level level) {
        return records.stream().filter(r -> r.getLevel().equals(level)).toList();
    }
}
