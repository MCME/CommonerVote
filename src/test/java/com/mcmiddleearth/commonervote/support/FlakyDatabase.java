package com.mcmiddleearth.commonervote.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import com.mcmiddleearth.commonervote.storage.ConnectionFactory;

/**
 * A {@link ConnectionFactory} in front of a real one, which a test can make fail as a database that is down or has a
 * short outage. While failures are pending, each attempt to connect throws an {@link SQLException}, as an unreachable
 * MariaDB does, instead of opening a connection.
 */
public final class FlakyDatabase implements ConnectionFactory {

    private final ConnectionFactory real;
    private final AtomicInteger failuresLeft = new AtomicInteger();

    public FlakyDatabase(ConnectionFactory real) {
        this.real = Objects.requireNonNull(real);
    }

    /** Makes the next {@code attempts} attempts to connect fail; the ones after that connect again. */
    public void failNext(int attempts) {
        failuresLeft.set(attempts);
    }

    /** Makes every attempt to connect fail until {@link #recover()}. */
    public void goDown() {
        failNext(Integer.MAX_VALUE);
    }

    /** Connects again from the next attempt on. */
    public void recover() {
        failNext(0);
    }

    @Override
    public Connection open() throws SQLException {
        if (failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            throw new SQLException("Communications link failure");
        }
        return real.open();
    }
}
