package com.mcmiddleearth.commonervote.storage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.storage.PromotionRecord.Source;
import com.mcmiddleearth.commonervote.support.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionStoreTest {

    private static final long NOW = 1_800_000_000_000L;

    private static final UUID P1 = uuid(1);
    private static final UUID P2 = uuid(2);

    private ConnectionFactory db;
    private PromotionStore store;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.fresh();
        Schema.create(db);
        store = new PromotionStore(db);
    }

    // ---- find / save ----

    @Test
    void find_is_empty_for_a_player_who_was_not_promoted() throws SQLException {
        store.save(new PromotionRecord(P1, NOW, Source.AUTO, false));

        assertEquals(Optional.empty(), store.find(P2));
    }

    @Test
    void save_find_round_trip() throws SQLException {
        store.save(new PromotionRecord(P1, NOW, Source.AUTO, false));
        store.save(new PromotionRecord(P2, NOW + 1, Source.MANUAL, true));
        UUID third = uuid(3);
        store.save(new PromotionRecord(third, NOW + 2, Source.DETECTED, false));

        assertEquals(Optional.of(new PromotionRecord(P1, NOW, Source.AUTO, false)), store.find(P1));
        assertEquals(Optional.of(new PromotionRecord(P2, NOW + 1, Source.MANUAL, true)), store.find(P2));
        assertEquals(Optional.of(new PromotionRecord(third, NOW + 2, Source.DETECTED, false)), store.find(third));
        assertEquals("auto", rawSource(P1), "the source is stored in lower case");
        assertEquals("manual", rawSource(P2));
        assertEquals("detected", rawSource(third));
        assertEquals(P1.toString(), rawPlayer(P1), "the player is stored as the 36-character UUID text");
    }

    @Test
    void save_replaces() throws SQLException {
        store.save(new PromotionRecord(P1, NOW, Source.DETECTED, true));

        store.save(new PromotionRecord(P1, NOW + 5000, Source.MANUAL, false));

        assertEquals(1, rowCount(P1), "one row, not two");
        assertEquals(Optional.of(new PromotionRecord(P1, NOW + 5000, Source.MANUAL, false)), store.find(P1));
    }

    @Test
    void save_does_not_touch_other_players() throws SQLException {
        store.save(new PromotionRecord(P2, NOW, Source.AUTO, true));

        store.save(new PromotionRecord(P1, NOW + 1, Source.MANUAL, false));
        store.save(new PromotionRecord(P1, NOW + 2, Source.AUTO, false));

        assertEquals(Optional.of(new PromotionRecord(P2, NOW, Source.AUTO, true)), store.find(P2));
    }

    @Test
    void save_is_one_transaction() throws SQLException {
        store.save(new PromotionRecord(P1, NOW, Source.AUTO, true));

        PromotionStore failing = new PromotionStore(failingOnInsert(() -> new SQLException("disk full")));
        SQLException e = assertThrows(SQLException.class,
                () -> failing.save(new PromotionRecord(P1, NOW + 5000, Source.MANUAL, false)));

        assertEquals("disk full", e.getMessage());
        assertEquals(Optional.of(new PromotionRecord(P1, NOW, Source.AUTO, true)), store.find(P1),
                "the DELETE was rolled back with the failed INSERT");
        // And the row is not left locked for the next save.
        store.save(new PromotionRecord(P1, NOW + 6000, Source.MANUAL, false));
        assertEquals(Optional.of(new PromotionRecord(P1, NOW + 6000, Source.MANUAL, false)), store.find(P1));
    }

    @Test
    void an_unknown_stored_source_reads_as_detected() throws SQLException {
        TestDatabase.exec(db, "INSERT INTO commonervote_promotions (player, promoted_at, source, welcomed) VALUES ('"
                + P1 + "', " + NOW + ", 'hand-edited', TRUE)");

        assertEquals(Optional.of(new PromotionRecord(P1, NOW, Source.DETECTED, true)), store.find(P1),
                "a hand-edited row must not break a join, so it is read as detected");
    }

    // ---- markWelcomed ----

    @Test
    void mark_welcomed() throws SQLException {
        store.save(new PromotionRecord(P1, NOW, Source.AUTO, false));
        store.save(new PromotionRecord(P2, NOW, Source.AUTO, false));

        store.markWelcomed(P1);

        assertEquals(Optional.of(new PromotionRecord(P1, NOW, Source.AUTO, true)), store.find(P1));
        assertFalse(store.find(P2).orElseThrow().welcomed(), "only the named player is marked");
        // Marking twice changes nothing.
        store.markWelcomed(P1);
        assertTrue(store.find(P1).orElseThrow().welcomed());
    }

    @Test
    void mark_welcomed_for_a_player_without_a_row_creates_nothing() throws SQLException {
        store.markWelcomed(P1);

        assertEquals(Optional.empty(), store.find(P1));
        assertEquals(0, rowCount(P1));
    }

    // ---- delete ----

    @Test
    void delete() throws SQLException {
        store.save(new PromotionRecord(P1, NOW, Source.AUTO, false));
        store.save(new PromotionRecord(P2, NOW, Source.MANUAL, false));

        store.delete(P1);

        assertEquals(Optional.empty(), store.find(P1));
        assertEquals(0, rowCount(P1));
        assertTrue(store.find(P2).isPresent(), "other players keep their rows");
    }

    @Test
    void delete_of_a_player_without_a_row_is_not_an_error() throws SQLException {
        store.delete(P1);

        assertEquals(Optional.empty(), store.find(P1));
    }

    // ---- helpers ----

    private String rawSource(UUID player) throws SQLException {
        return rawString("source", player);
    }

    private String rawPlayer(UUID player) throws SQLException {
        return rawString("player", player);
    }

    private String rawString(String column, UUID player) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM commonervote_promotions WHERE player = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "a row for " + player);
                return rs.getString(1);
            }
        }
    }

    private int rowCount(UUID player) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM commonervote_promotions WHERE player = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Connections that throw {@code failure} on an INSERT, and behave normally otherwise. */
    private ConnectionFactory failingOnInsert(Supplier<Throwable> failure) {
        return () -> {
            Connection real = db.open();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && sql.startsWith("INSERT")) {
                            throw failure.get();
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        };
    }

    private static UUID uuid(long n) {
        return new UUID(0, n);
    }
}
