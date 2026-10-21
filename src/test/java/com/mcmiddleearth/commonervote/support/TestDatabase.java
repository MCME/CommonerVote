package com.mcmiddleearth.commonervote.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import com.mcmiddleearth.commonervote.storage.ConnectionFactory;

/**
 * In-memory databases for the storage tests.
 * <p>
 * H2 in MariaDB mode stands in for the production MariaDB. The URL was probe-verified against 1.2.9's votes table
 * DDL, {@code SELECT ... FOR UPDATE} and the rescale {@code UPDATE}; {@code NON_KEYWORDS} lets the settings table
 * keep its column called {@code value} and the votes table its column called {@code timestamp}.
 */
public final class TestDatabase {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TestDatabase() {
    }

    /**
     * A factory for a new, empty database of its own. Every call gets a different name, so tests never see each
     * other's tables. {@code DB_CLOSE_DELAY=-1} keeps the data alive between the connections one test opens.
     */
    public static ConnectionFactory fresh() {
        String url = "jdbc:h2:mem:commonervote" + COUNTER.incrementAndGet()
                + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE,TIMESTAMP;DB_CLOSE_DELAY=-1";
        return () -> DriverManager.getConnection(url);
    }

    /** Runs one statement on a connection of its own. */
    public static void exec(ConnectionFactory f, String sql) throws SQLException {
        try (Connection c = f.open(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
