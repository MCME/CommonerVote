package com.mcmiddleearth.commonervote.storage;

import java.sql.SQLException;

/**
 * The database could not be reached or did not answer: what a caller of {@link DbExecutor#supply} gets when the work
 * failed with an {@link SQLException}, which is the cause. The player is told to try again in a minute.
 * <p>
 * Only an {@code SQLException} becomes this. Anything else that goes wrong in database work is a bug or corrupt data,
 * and reaches the caller as it is.
 */
public final class DatabaseUnavailableException extends RuntimeException {

    public DatabaseUnavailableException(SQLException cause) {
        super("The vote database is unreachable: " + cause.getMessage(), cause);
    }
}
