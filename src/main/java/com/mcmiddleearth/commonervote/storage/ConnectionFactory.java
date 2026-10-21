package com.mcmiddleearth.commonervote.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens a new JDBC connection each time it is asked. The plugin keeps no pooled or long-lived connection: every
 * unit of work opens one, uses it and closes it, so a database restart never leaves a dead connection behind.
 */
@FunctionalInterface
public interface ConnectionFactory {

    /** A new connection that the caller must close. */
    Connection open() throws SQLException;

    /**
     * Connections to the database in {@code c}, through {@link DriverManager}. The plugin ships no driver: it
     * relies on the MySQL driver that Paper has on the server classpath.
     */
    static ConnectionFactory of(DatabaseConfig c) {
        String url = c.jdbcUrl();
        String user = c.user();
        String password = c.password();
        return () -> DriverManager.getConnection(url, user, password);
    }
}
