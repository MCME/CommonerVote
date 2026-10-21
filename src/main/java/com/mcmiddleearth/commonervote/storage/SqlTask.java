package com.mcmiddleearth.commonervote.storage;

import java.sql.SQLException;

/**
 * A piece of database work that returns a result: what {@link DbExecutor#supply} runs on the database thread.
 *
 * @param <T> the result type
 */
@FunctionalInterface
public interface SqlTask<T> {

    T run() throws SQLException;
}
