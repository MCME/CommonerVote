package com.mcmiddleearth.commonervote.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the plugin's three tables. Safe to run on every start.
 * <p>
 * The votes table is 1.2.9's, character for character. The 2020 plugin may still be running against the same
 * database, so it must never gain a column or an index. Only the settings and promotions tables are new.
 */
public final class Schema {

    private static final String VOTES = "CREATE TABLE IF NOT EXISTS commonervote_votes "
            + "(voter VARCHAR(50), recipient VARCHAR(50), timestamp BIGINT(20), weight DOUBLE, reason VARCHAR(150))";

    private static final String SETTINGS = "CREATE TABLE IF NOT EXISTS commonervote_settings "
            + "(name VARCHAR(64) PRIMARY KEY, value TEXT NOT NULL)";

    /** {@code source} is {@code auto}, {@code manual} or {@code detected}. */
    private static final String PROMOTIONS = "CREATE TABLE IF NOT EXISTS commonervote_promotions "
            + "(player VARCHAR(36) PRIMARY KEY, promoted_at BIGINT NOT NULL, source VARCHAR(16) NOT NULL, "
            + "welcomed BOOLEAN NOT NULL)";

    private Schema() {
    }

    public static void create(ConnectionFactory f) throws SQLException {
        try (Connection c = f.open(); Statement st = c.createStatement()) {
            st.executeUpdate(VOTES);
            st.executeUpdate(SETTINGS);
            st.executeUpdate(PROMOTIONS);
        }
    }
}
