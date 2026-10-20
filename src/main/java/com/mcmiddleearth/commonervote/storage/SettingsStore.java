package com.mcmiddleearth.commonervote.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.VoteMath;

/**
 * Reads and writes the {@code commonervote_settings} table, which every backend of the network shares.
 * <p>
 * Every method opens its own connection and closes it again, and the writes are one transaction each, so a failure
 * leaves the table as it was. Only plain SQL that both MariaDB and the H2 of the tests understand is used.
 * <p>
 * Stored weights are normalised by {@code votesNeeded} (a vote is worth {@code weight / votesNeeded} of a
 * promotion). Changing {@code votesNeeded} therefore rescales every stored weight in the same transaction as the
 * change, so a vote keeps the same worth in whole votes.
 */
public final class SettingsStore {

    private final ConnectionFactory factory;

    public SettingsStore(ConnectionFactory factory) {
        this.factory = factory;
    }

    /** Every stored setting as name and value, in no particular order. Empty when nothing is stored yet. */
    public Map<String, String> load() throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (Connection c = factory.open();
             PreparedStatement ps = c.prepareStatement("SELECT name, value FROM commonervote_settings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                values.put(rs.getString(1), rs.getString(2));
            }
        }
        return values;
    }

    /**
     * Stores each of {@code defaults} that has no row yet; rows that exist are left as they are, so an edited
     * setting is never reset.
     */
    public void seedMissing(Map<String, String> defaults) throws SQLException {
        try {
            insertMissing(defaults);
        } catch (SQLException first) {
            // Backends that start together on a fresh database all seed at once, and the loser of a row gets a
            // primary key clash. The second attempt reads the winner's rows and inserts only what is still
            // missing; if the database is really down, it fails too.
            try {
                insertMissing(defaults);
            } catch (SQLException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    /**
     * Writes all settings. When {@code votesNeeded} differs from the stored value, every vote weight is rescaled
     * first, in the same transaction. If anything fails, nothing is changed.
     *
     * @throws IllegalArgumentException if {@link Settings#problems()} is not empty, before any connection is
     *                                  opened. A {@code votesNeeded} outside its range must never be stored: it
     *                                  would rescale the weights, and {@link Settings#fromMap} would then read it
     *                                  back as the default, leaving every vote worth the wrong share for good.
     */
    public void save(Settings s) throws SQLException {
        List<String> problems = s.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Settings not saved: " + String.join(" ", problems));
        }
        Map<String, String> values = s.toMap();
        inTransaction(c -> {
            // The lock serialises two saves, so neither can rescale from a value the other is changing, and it
            // is the same lock a vote takes first (see lockWeightSettings).
            int stored = lockWeightSettings(c).votesNeeded();
            if (stored != s.votesNeeded()) {
                rescaleVotes(c, stored, s.votesNeeded());
            }
            write(c, values);
        });
    }

    private void insertMissing(Map<String, String> defaults) throws SQLException {
        inTransaction(c -> {
            Set<String> present = new TreeSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM commonervote_settings");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    present.add(rs.getString(1));
                }
            }
            Map<String, String> missing = new LinkedHashMap<>(defaults);
            missing.keySet().removeAll(present);
            if (missing.isEmpty()) {
                return;
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO commonervote_settings (name, value) VALUES (?, ?)")) {
                for (Map.Entry<String, String> e : missing.entrySet()) {
                    insert.setString(1, e.getKey());
                    insert.setString(2, e.getValue());
                    insert.executeUpdate();
                }
            }
        });
    }

    /**
     * Reads the three settings that decide a vote's weight ({@code votesNeeded}, {@code staffWeight},
     * {@code teamWeight}), with a row lock on them until the transaction ends. A missing or unusable value is read
     * the way {@link Settings#fromMap} reads it, as the default; for {@code votesNeeded} that is the value the votes
     * in the table were written under.
     * <p>
     * <b>This one statement is the first thing both {@link #save} and {@link VoteStore#cast} do, and it must stay
     * the only way either of them takes its first settings lock.</b> InnoDB takes the rows of a statement in key
     * order. If the two transactions locked different rows, or the same rows in different statements, each could
     * hold one row and wait for the other's (a deadlock, and the loser's player is told the database is
     * unreachable). With the same statement first, the second transaction waits on the first row while holding
     * nothing, and just queues behind the first.
     */
    static Settings lockWeightSettings(Connection c) throws SQLException {
        Map<String, String> stored = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT name, value FROM commonervote_settings "
                + "WHERE name IN ('votesNeeded','staffWeight','teamWeight') FOR UPDATE");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                stored.put(rs.getString(1), rs.getString(2));
            }
        }
        return Settings.fromMap(stored, message -> { });
    }

    /** Converts every stored weight from {@code from} to {@code to} votes needed, as {@link VoteMath#rescale}. */
    private static void rescaleVotes(Connection c, int from, int to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE commonervote_votes SET weight = weight * ? / ?")) {
            ps.setInt(1, from);
            ps.setInt(2, to);
            ps.executeUpdate();
        }
    }

    /** An update per setting, and an insert for the ones that had no row (no dialect-only upsert). */
    private static void write(Connection c, Map<String, String> values) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("UPDATE commonervote_settings SET value = ? WHERE name = ?");
             PreparedStatement insert = c.prepareStatement(
                     "INSERT INTO commonervote_settings (name, value) VALUES (?, ?)")) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                update.setString(1, e.getValue());
                update.setString(2, e.getKey());
                if (update.executeUpdate() == 0) {
                    insert.setString(1, e.getKey());
                    insert.setString(2, e.getValue());
                    insert.executeUpdate();
                }
            }
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(Connection c) throws SQLException;
    }

    /** Runs {@code work} on a new connection as one transaction: committed if it returns, rolled back if it throws. */
    private void inTransaction(Work work) throws SQLException {
        Transactions.run(factory, c -> {
            work.run(c);
            return null;
        });
    }
}
