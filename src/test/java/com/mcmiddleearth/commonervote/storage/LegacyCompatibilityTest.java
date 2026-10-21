package com.mcmiddleearth.commonervote.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.Vote;
import com.mcmiddleearth.commonervote.domain.VoteMath;
import com.mcmiddleearth.commonervote.support.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.0 and 1.2.9 may share one database for a while, so a row written by either must be readable by the other. The SQL
 * strings below are 1.2.9's own, copied from its {@code DatabaseVoteStorage}, and are bound the way 1.2.9 bound them.
 */
class LegacyCompatibilityTest {

    // 1.2.9's statements, verbatim.
    private static final String LEGACY_INSERT = "INSERT INTO commonervote_votes VALUES (?,?,?,?,?)";
    private static final String LEGACY_SELECT_BY_RECIPIENT = "SELECT * FROM commonervote_votes WHERE recipient = ?";
    private static final String LEGACY_MAX_WEIGHT =
            "SELECT MAX(weight) FROM commonervote_votes WHERE voter = ? AND recipient = ?";

    private static final double EPS = 1e-9;
    private static final long NOW = 1_800_000_000_000L;
    private static final long SINCE = VoteMath.validSince(NOW, 100);

    private static final UUID VOTER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_VOTER = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID RECIPIENT = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    private ConnectionFactory db;
    private VoteStore store;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.fresh();
        Schema.create(db);
        new SettingsStore(db).seedMissing(Settings.defaults().toMap());
        store = new VoteStore(db);
    }

    @Test
    void rows_written_by_1_2_9_are_read_by_2_0() throws SQLException {
        legacyInsert(VOTER, RECIPIENT, NOW - 2000, 0.4, "great houses");
        legacyInsert(OTHER_VOTER, RECIPIENT, NOW - 1000, 0.2, "");

        List<Vote> votes = store.validVotesFor(RECIPIENT, SINCE);

        assertEquals(List.of(
                new Vote(OTHER_VOTER, RECIPIENT, NOW - 1000, 0.2, ""),
                new Vote(VOTER, RECIPIENT, NOW - 2000, 0.4, "great houses")), votes);
        assertEquals(0.6, VoteMath.score(votes, SINCE), EPS);
    }

    @Test
    void a_1_2_9_row_without_a_reason_is_read_as_an_empty_reason() throws SQLException {
        legacyInsert(VOTER, RECIPIENT, NOW, 0.2, null);

        assertEquals("", store.validVotesFor(RECIPIENT, SINCE).get(0).reason());
    }

    @Test
    void a_vote_recast_by_2_0_replaces_the_1_2_9_row() throws SQLException {
        legacyInsert(VOTER, RECIPIENT, NOW - 2000, 0.4, "old");

        VoteStore.CastResult result = store.cast(VOTER, RECIPIENT, false, "new", NOW, SINCE);

        assertTrue(result.replacedValidVote());
        assertEquals(0.4, result.vote().weight(), EPS, "the 1.2.9 weight is the max, so it stays");
        assertEquals(1, legacyVotesFor(RECIPIENT).size());
    }

    @Test
    void rows_written_by_2_0_are_read_by_1_2_9() throws SQLException {
        store.cast(VOTER, RECIPIENT, true, "good builder", NOW, SINCE);
        store.cast(OTHER_VOTER, RECIPIENT, false, null, NOW - 500, SINCE);

        List<Object[]> rows = legacyVotesFor(RECIPIENT);

        assertEquals(2, rows.size());
        Object[] staff = rowOf(rows, VOTER);
        assertEquals(RECIPIENT.toString(), staff[1]);
        assertEquals(NOW, staff[2]);
        assertEquals(0.4, (Double) staff[3], EPS);
        assertEquals("good builder", staff[4]);
        Object[] team = rowOf(rows, OTHER_VOTER);
        assertEquals(NOW - 500, team[2]);
        assertEquals(0.2, (Double) team[3], EPS);
        assertNotNull(team[4], "1.2.9 needs a reason that is not NULL");
        assertEquals("", team[4]);
    }

    @Test
    void the_columns_of_a_2_0_row_are_in_the_1_2_9_order() throws SQLException {
        store.cast(VOTER, RECIPIENT, true, "r", NOW, SINCE);

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(LEGACY_SELECT_BY_RECIPIENT)) {
            ps.setString(1, RECIPIENT.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                // 1.2.9 reads by name; a tool or a future reader may read by position, so both must agree.
                assertEquals(VOTER.toString(), rs.getString(1));
                assertEquals(RECIPIENT.toString(), rs.getString(2));
                assertEquals(NOW, rs.getLong(3));
                assertEquals(0.4, rs.getDouble(4), EPS);
                assertEquals("r", rs.getString(5));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void the_max_weight_rule_holds_for_1_2_9() throws SQLException {
        store.cast(VOTER, RECIPIENT, true, "", NOW - 1000, SINCE);
        store.cast(VOTER, RECIPIENT, false, "", NOW, SINCE);

        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(LEGACY_MAX_WEIGHT)) {
            ps.setString(1, VOTER.toString());
            ps.setString(2, RECIPIENT.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0.4, rs.getDouble(1), EPS);
            }
        }
    }

    // ---- 1.2.9's own statements ----

    /** Bound the way 1.2.9's {@code addVote} bound them. */
    private void legacyInsert(UUID voter, UUID recipient, long timestamp, double weight, String reason)
            throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(LEGACY_INSERT)) {
            ps.setString(1, voter.toString());
            ps.setString(2, recipient.toString());
            ps.setLong(3, timestamp);
            ps.setDouble(4, weight);
            ps.setString(5, reason);
            ps.executeUpdate();
        }
    }

    /** What 1.2.9's {@code getPlayerVotes} reads: voter, recipient, timestamp, weight, reason, by column name. */
    private List<Object[]> legacyVotesFor(UUID recipient) throws SQLException {
        List<Object[]> rows = new java.util.ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(LEGACY_SELECT_BY_RECIPIENT)) {
            ps.setString(1, recipient.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[] {rs.getString("voter"), rs.getString("recipient"), rs.getLong("timestamp"),
                        rs.getDouble("weight"), rs.getString("reason")});
                }
            }
        }
        return rows;
    }

    private static Object[] rowOf(List<Object[]> rows, UUID voter) {
        return rows.stream().filter(r -> r[0].equals(voter.toString())).findFirst().orElseThrow();
    }
}
