package com.mcmiddleearth.commonervote.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runs a piece of work as one database transaction. Shared by the stores, so there is a single place that decides how
 * a transaction is opened, committed and abandoned.
 */
final class Transactions {

    @FunctionalInterface
    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private Transactions() {
    }

    /**
     * Runs {@code work} on a new connection from {@code factory} as one transaction: committed if it returns, rolled
     * back if it throws. The connection is always closed. A failing rollback is added as suppressed to the exception
     * that caused it, which is the one that is thrown.
     */
    static <T> T run(ConnectionFactory factory, Work<T> work) throws SQLException {
        try (Connection c = factory.open()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            boolean settled = false;
            try {
                T result = work.run(c);
                c.commit();
                settled = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                    settled = true;
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                // Switching autocommit back on would commit whatever is still pending, so only do it once the
                // transaction is settled one way or the other.
                if (settled) {
                    try {
                        c.setAutoCommit(autoCommit);
                    } catch (SQLException ignored) {
                        // The connection is closed right after this, and the outcome above stands.
                    }
                }
            }
        }
    }
}
