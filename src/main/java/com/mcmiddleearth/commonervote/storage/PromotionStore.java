package com.mcmiddleearth.commonervote.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mcmiddleearth.commonervote.storage.PromotionRecord.Source;

/**
 * Reads and writes the {@code commonervote_promotions} table: who was promoted, when, how, and whether they have seen
 * the welcome dialog. A player with a row here is no longer a candidate (see {@link VoteStore#candidates}).
 * <p>
 * Every method opens its own connection and closes it again. Only plain SQL that both MariaDB and the H2 of the tests
 * understand is used. Players are stored as the 36-character form of their UUID, and {@link Source} as its lower-case
 * name.
 */
public final class PromotionStore {

    private final ConnectionFactory factory;

    public PromotionStore(ConnectionFactory factory) {
        this.factory = factory;
    }

    /** The promotion record of {@code player}; empty if they have none. */
    public Optional<PromotionRecord> find(UUID player) throws SQLException {
        try (Connection c = factory.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT promoted_at, source, welcomed FROM commonervote_promotions WHERE player = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PromotionRecord(player, rs.getLong(1), sourceOf(rs.getString(2)),
                        rs.getBoolean(3)));
            }
        }
    }

    /**
     * Stores {@code record}, replacing the player's existing row if there is one. One transaction (a DELETE and an
     * INSERT, as there is no upsert both databases share), so a failure leaves the old row as it was.
     */
    public void save(PromotionRecord record) throws SQLException {
        String player = record.player().toString();
        Transactions.run(factory, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM commonervote_promotions WHERE player = ?")) {
                ps.setString(1, player);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO commonervote_promotions "
                    + "(player, promoted_at, source, welcomed) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, player);
                ps.setLong(2, record.promotedAt());
                ps.setString(3, record.source().name().toLowerCase(Locale.ROOT));
                ps.setBoolean(4, record.welcomed());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes the player's record, if any. */
    public void delete(UUID player) throws SQLException {
        try (Connection c = factory.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM commonervote_promotions WHERE player = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
    }

    /** Marks the player as having seen the welcome dialog. Does nothing if they have no record. */
    public void markWelcomed(UUID player) throws SQLException {
        try (Connection c = factory.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE commonervote_promotions SET welcomed = ? WHERE player = ?")) {
            ps.setBoolean(1, true);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    /**
     * The {@link Source} stored as {@code stored}. A value that is none of them (a hand-edited row) reads as
     * {@link Source#DETECTED} instead of failing, so one bad row cannot break a read of the player.
     */
    private static Source sourceOf(String stored) {
        for (Source source : Source.values()) {
            if (source.name().equalsIgnoreCase(stored)) {
                return source;
            }
        }
        return Source.DETECTED;
    }
}
