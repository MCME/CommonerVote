package com.mcmiddleearth.commonervote.storage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.Vote;
import com.mcmiddleearth.commonervote.domain.VoteMath;
import com.mcmiddleearth.commonervote.support.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoteStoreTest {

    private static final double EPS = 1e-9;
    private static final long NOW = 1_800_000_000_000L;
    private static final long SINCE = VoteMath.validSince(NOW, 100);
    private static final long DAY = 86_400_000L;

    private static final UUID V1 = uuid(101);
    private static final UUID V2 = uuid(102);
    private static final UUID V3 = uuid(103);
    private static final UUID R1 = uuid(1);
    private static final UUID R2 = uuid(2);
    private static final UUID R3 = uuid(3);
    private static final UUID R4 = uuid(4);

    private ConnectionFactory db;
    private VoteStore store;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.fresh();
        Schema.create(db);
        new SettingsStore(db).seedMissing(Settings.defaults().toMap());
        store = new VoteStore(db);
    }

    // ---- cast ----

    @Test
    void cast_stores_normalised_weight() throws SQLException {
        store.cast(V1, R1, true, "good builder", NOW, SINCE);
        store.cast(V2, R1, false, null, NOW, SINCE);
        store.cast(V3, R1, false, "   \t ", NOW, SINCE);

        assertEquals(0.4, weightOf(V1, R1), EPS);
        assertEquals(0.2, weightOf(V2, R1), EPS);
        assertEquals("good builder", reasonOf(V1, R1));
        assertEquals("", reasonOf(V2, R1), "a null reason is stored as an empty string");
        assertEquals("", reasonOf(V3, R1), "a blank reason is stored as an empty string");
    }

    @Test
    void cast_returns_the_stored_vote() throws SQLException {
        VoteStore.CastResult result = store.cast(V1, R1, true, "  nice  ", NOW, SINCE);

        assertEquals(new Vote(V1, R1, NOW, 0.4, "nice"), result.vote());
    }

    @Test
    void a_long_reason_is_cut_to_150_characters_and_trimmed() throws SQLException {
        store.cast(V1, R1, false, "a".repeat(200), NOW, SINCE);
        store.cast(V2, R1, false, "a".repeat(149) + " " + "b".repeat(50), NOW, SINCE);
        store.cast(V3, R1, false, "   " + "c".repeat(200), NOW, SINCE);

        assertEquals("a".repeat(150), reasonOf(V1, R1));
        assertEquals("a".repeat(149), reasonOf(V2, R1), "the space at the cut is trimmed away");
        assertEquals("c".repeat(150), reasonOf(V3, R1), "the leading blanks do not count towards the 150");
    }

    @Test
    void the_cut_never_splits_a_character_outside_the_basic_plane() throws SQLException {
        // A character outside the basic plane is two chars in Java. Cut between them, it would be stored as a
        // broken half.
        String smiley = new String(Character.toChars(0x1F600));
        store.cast(V1, R1, false, "a".repeat(149) + smiley + "b".repeat(10), NOW, SINCE);
        store.cast(V2, R1, false, "a".repeat(148) + smiley + "b".repeat(10), NOW, SINCE);

        assertEquals("a".repeat(149), reasonOf(V1, R1), "the whole character is dropped, not half of it");
        assertEquals("a".repeat(148) + smiley, reasonOf(V2, R1), "a character that fits is kept");
    }

    @Test
    void cast_reads_votes_needed_from_db() throws SQLException {
        TestDatabase.exec(db, "UPDATE commonervote_settings SET value = '10' WHERE name = 'votesNeeded'");

        VoteStore.CastResult result = store.cast(V1, R1, false, "", NOW, SINCE);

        assertEquals(0.1, result.vote().weight(), EPS);
        assertEquals(0.1, weightOf(V1, R1), EPS);
    }

    @Test
    void cast_reads_the_weights_from_db() throws SQLException {
        TestDatabase.exec(db, "UPDATE commonervote_settings SET value = '3' WHERE name = 'staffWeight'");
        TestDatabase.exec(db, "UPDATE commonervote_settings SET value = '2' WHERE name = 'teamWeight'");

        store.cast(V1, R1, true, "", NOW, SINCE);
        store.cast(V2, R1, false, "", NOW, SINCE);

        assertEquals(0.6, weightOf(V1, R1), EPS);
        assertEquals(0.4, weightOf(V2, R1), EPS);
    }

    @Test
    void missing_or_unusable_stored_settings_mean_the_defaults() throws SQLException {
        TestDatabase.exec(db, "UPDATE commonervote_settings SET value = 'many' WHERE name = 'votesNeeded'");
        TestDatabase.exec(db, "UPDATE commonervote_settings SET value = '99' WHERE name = 'staffWeight'");
        TestDatabase.exec(db, "DELETE FROM commonervote_settings WHERE name = 'teamWeight'");

        store.cast(V1, R1, true, "", NOW, SINCE);
        store.cast(V2, R1, false, "", NOW, SINCE);

        assertEquals(0.4, weightOf(V1, R1), EPS, "staffWeight 99 is out of range, so 2/5");
        assertEquals(0.2, weightOf(V2, R1), EPS, "a missing teamWeight is 1/5");
    }

    @Test
    void cast_works_with_an_empty_settings_table() throws SQLException {
        TestDatabase.exec(db, "DELETE FROM commonervote_settings");

        store.cast(V1, R1, true, "", NOW, SINCE);

        assertEquals(0.4, weightOf(V1, R1), EPS);
    }

    @Test
    void recast_replaces_and_keeps_max_weight() throws SQLException {
        VoteStore.CastResult first = store.cast(V1, R1, true, "first", NOW - 1000, SINCE);
        VoteStore.CastResult second = store.cast(V1, R1, false, "second", NOW, SINCE);

        assertEquals(1, countRows(V1, R1), "the recast replaces the row");
        assertEquals(0.4, weightOf(V1, R1), EPS, "a lower weight never replaces a higher one");
        assertEquals("second", reasonOf(V1, R1));
        assertEquals(NOW, timestampOf(V1, R1));

        assertFalse(first.replacedValidVote());
        assertEquals(0.0, first.scoreBefore(), EPS);
        assertEquals(0.4, first.scoreAfter(), EPS);

        assertTrue(second.replacedValidVote());
        assertEquals(0.4, second.scoreBefore(), EPS);
        assertEquals(0.4, second.scoreAfter(), EPS);
        assertEquals(0.4, second.vote().weight(), EPS);
    }

    @Test
    void a_recast_can_raise_the_weight() throws SQLException {
        store.cast(V1, R1, false, "", NOW - 1000, SINCE);
        VoteStore.CastResult second = store.cast(V1, R1, true, "", NOW, SINCE);

        assertEquals(0.4, weightOf(V1, R1), EPS);
        assertEquals(0.2, second.scoreBefore(), EPS);
        assertEquals(0.4, second.scoreAfter(), EPS);
    }

    @Test
    void the_score_counts_the_other_voters_votes() throws SQLException {
        store.cast(V2, R1, true, "", NOW - 2000, SINCE);
        store.cast(V1, R1, false, "", NOW - 1000, SINCE);

        VoteStore.CastResult result = store.cast(V1, R1, false, "", NOW, SINCE);

        assertEquals(0.6, result.scoreBefore(), EPS);
        assertEquals(0.6, result.scoreAfter(), EPS);
        assertEquals(2, rowCount(), "one row per voter");
    }

    @Test
    void a_cast_for_one_recipient_does_not_touch_the_others() throws SQLException {
        store.cast(V1, R2, true, "", NOW - 1000, SINCE);

        VoteStore.CastResult result = store.cast(V1, R1, true, "", NOW, SINCE);

        assertEquals(0.0, result.scoreBefore(), EPS);
        assertFalse(result.replacedValidVote());
        assertEquals(0.4, weightOf(V1, R2), EPS);
        assertEquals(2, rowCount());
    }

    @Test
    void recasting_over_an_expired_vote_is_not_a_valid_replacement() throws SQLException {
        insert(V1, R1, SINCE - DAY, 0.4, "old");
        insert(V2, R1, NOW - DAY, 0.2, "recent");

        VoteStore.CastResult result = store.cast(V1, R1, true, "again", NOW, SINCE);

        assertFalse(result.replacedValidVote());
        assertEquals(0.2, result.scoreBefore(), EPS, "the expired vote does not count");
        assertEquals(0.6, result.scoreAfter(), EPS);
        assertEquals(1, countRows(V1, R1), "the expired row is replaced, not kept next to the new one");
        assertEquals(NOW, timestampOf(V1, R1));
    }

    @Test
    void a_vote_cast_exactly_at_the_validity_limit_is_valid() throws SQLException {
        insert(V1, R1, SINCE, 0.4, "");

        VoteStore.CastResult result = store.cast(V1, R1, true, "", NOW, SINCE);

        assertTrue(result.replacedValidVote());
        assertEquals(0.4, result.scoreBefore(), EPS);
    }

    @Test
    void duplicate_legacy_rows_for_one_voter_are_all_replaced() throws SQLException {
        insert(V1, R1, NOW - 2000, 0.2, "one");
        insert(V1, R1, NOW - 1000, 0.4, "two");
        insert(V2, R1, NOW - 1000, 0.2, "other");

        VoteStore.CastResult result = store.cast(V1, R1, false, "", NOW, SINCE);

        assertEquals(1, countRows(V1, R1));
        assertEquals(0.4, weightOf(V1, R1), EPS, "the max of the old rows is kept");
        assertEquals(0.8, result.scoreBefore(), EPS);
        assertEquals(0.6, result.scoreAfter(), EPS, "0.8 less both old rows (0.6) plus the new 0.4");
    }

    @Test
    void cast_rolls_back_on_failure() throws SQLException {
        store.cast(V1, R1, true, "keep me", NOW - 5000, SINCE);

        VoteStore failing = new VoteStore(failingOnInsert(() -> new SQLException("disk full")));
        SQLException e = assertThrows(SQLException.class, () -> failing.cast(V1, R1, false, "new", NOW, SINCE));

        assertEquals("disk full", e.getMessage());
        assertEquals(1, countRows(V1, R1), "the previous row is still there");
        assertEquals(0.4, weightOf(V1, R1), EPS);
        assertEquals("keep me", reasonOf(V1, R1));
        assertEquals(NOW - 5000, timestampOf(V1, R1));
        // And the table is not left locked or half-written for the next cast.
        VoteStore.CastResult next = store.cast(V1, R1, false, "", NOW, SINCE);
        assertTrue(next.replacedValidVote());
    }

    @Test
    void cast_rolls_back_on_an_unchecked_failure_too() throws SQLException {
        store.cast(V1, R1, true, "keep me", NOW - 5000, SINCE);

        VoteStore failing = new VoteStore(failingOnInsert(() -> new IllegalStateException("bug")));
        assertThrows(IllegalStateException.class, () -> failing.cast(V1, R1, false, "new", NOW, SINCE));

        assertEquals(1, countRows(V1, R1));
        assertEquals("keep me", reasonOf(V1, R1));
    }

    @Test
    void cast_leaves_nothing_behind_when_there_was_no_previous_row() throws SQLException {
        VoteStore failing = new VoteStore(failingOnInsert(() -> new SQLException("disk full")));
        assertThrows(SQLException.class, () -> failing.cast(V1, R1, false, "new", NOW, SINCE));

        assertEquals(0, rowCount());
    }

    // ---- withdraw and clear ----

    @Test
    void withdraw_and_clear() throws SQLException {
        store.cast(V1, R1, true, "", NOW, SINCE);
        store.cast(V2, R1, false, "", NOW, SINCE);
        store.cast(V3, R1, false, "", NOW, SINCE);
        store.cast(V1, R2, false, "", NOW, SINCE);

        assertTrue(store.withdraw(V1, R1));
        assertFalse(store.withdraw(V1, R1), "nothing left to withdraw");
        assertEquals(0, countRows(V1, R1));
        assertEquals(1, countRows(V1, R2), "only the one recipient's vote is withdrawn");

        assertEquals(2, store.clear(R1));
        assertEquals(0, store.clear(R1));
        assertEquals(1, rowCount(), "clearing one recipient leaves the other's votes");
    }

    // ---- reading ----

    @Test
    void valid_votes_are_returned_newest_first_with_every_field() throws SQLException {
        insert(V1, R1, NOW - 3000, 0.2, "oldest");
        insert(V2, R1, NOW - 1000, 0.4, "newest");
        insert(V3, R1, NOW - 2000, 0.2, "");

        List<Vote> votes = store.validVotesFor(R1, SINCE);

        assertEquals(List.of(
                new Vote(V2, R1, NOW - 1000, 0.4, "newest"),
                new Vote(V3, R1, NOW - 2000, 0.2, ""),
                new Vote(V1, R1, NOW - 3000, 0.2, "oldest")), votes);
    }

    @Test
    void valid_filters() throws SQLException {
        insert(V1, R1, NOW - DAY, 0.2, "valid");
        insert(V2, R1, SINCE - 1, 0.4, "expired by a millisecond");
        insert(V3, R1, SINCE, 0.2, "exactly at the limit");
        insert(V1, R2, SINCE - DAY, 0.4, "only expired");

        List<Vote> single = store.validVotesFor(R1, SINCE);
        Map<UUID, List<Vote>> batch = store.validVotesFor(Set.of(R1, R2), SINCE);

        assertEquals(Set.of(V1, V3), voters(single));
        assertEquals(Set.of(V1, V3), voters(batch.get(R1)));
        assertEquals(List.of(), batch.get(R2));
        assertEquals(List.of(), store.validVotesFor(R2, SINCE));

        List<RecipientScore> candidates = store.candidates(SINCE, 10);
        assertEquals(1, candidates.size(), "the recipient with only an expired vote is not a candidate");
        assertEquals(R1, candidates.get(0).recipient());
        assertEquals(0.4, candidates.get(0).score(), EPS, "the expired 0.4 does not count");

        // Expired rows stay in the table as history.
        assertEquals(4, rowCount());
        assertTrue(store.hasAnyVotes(R2));
        assertTrue(store.hasAnyVotes(R1));
        assertFalse(store.hasAnyVotes(R3));
    }

    @Test
    void batch_contains_all_keys() throws SQLException {
        store.cast(V1, R1, true, "", NOW, SINCE);

        Map<UUID, List<Vote>> result = store.validVotesFor(Set.of(R1, R2), SINCE);

        assertEquals(Set.of(R1, R2), result.keySet());
        assertEquals(1, result.get(R1).size());
        assertEquals(List.of(), result.get(R2));
    }

    @Test
    void batch_votes_go_to_their_own_recipient_newest_first() throws SQLException {
        insert(V1, R1, NOW - 3000, 0.2, "a");
        insert(V2, R1, NOW - 1000, 0.2, "b");
        insert(V1, R2, NOW - 2000, 0.4, "c");
        insert(V1, R3, NOW - 2000, 0.4, "not asked for");

        Map<UUID, List<Vote>> result = store.validVotesFor(List.of(R1, R2), SINCE);

        assertEquals(Set.of(R1, R2), result.keySet());
        assertEquals(List.of("b", "a"), reasons(result.get(R1)));
        assertEquals(List.of("c"), reasons(result.get(R2)));
    }

    @Test
    void batch_of_nothing_is_empty_and_never_touches_the_database() throws SQLException {
        AtomicInteger opened = new AtomicInteger();
        VoteStore counting = new VoteStore(() -> {
            opened.incrementAndGet();
            throw new SQLException("must not be opened");
        });

        assertEquals(Map.of(), counting.validVotesFor(Set.of(), SINCE));
        assertEquals(0, opened.get());
    }

    @Test
    void batch_ignores_a_repeated_recipient() throws SQLException {
        insert(V1, R1, NOW, 0.2, "x");

        Map<UUID, List<Vote>> result = store.validVotesFor(List.of(R1, R1, R2), SINCE);

        assertEquals(Set.of(R1, R2), result.keySet());
        assertEquals(1, result.get(R1).size());
    }

    @Test
    void a_null_reason_in_the_table_reads_as_empty() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO commonervote_votes VALUES (?,?,?,?,?)")) {
            ps.setString(1, V1.toString());
            ps.setString(2, R1.toString());
            ps.setLong(3, NOW);
            ps.setDouble(4, 0.2);
            ps.setString(5, null);
            ps.executeUpdate();
        }

        assertEquals("", store.validVotesFor(R1, SINCE).get(0).reason());
    }

    // ---- candidates ----

    @Test
    void candidates_order_and_exclusion() throws SQLException {
        insert(V1, R1, NOW, 0.4, "");
        insert(V2, R1, NOW, 0.4, "");
        insert(V1, R2, NOW, 0.4, "");
        insert(V1, R3, NOW, 0.2, "");
        insert(V2, R3, NOW, 0.2, "");
        insert(V1, R4, NOW, 0.4, "");

        List<RecipientScore> all = store.candidates(SINCE, 10);

        assertEquals(List.of(R1, R2, R3, R4), all.stream().map(RecipientScore::recipient).toList(),
                "highest first, ties by recipient UUID string ascending");
        assertEquals(0.8, all.get(0).score(), EPS);
        assertEquals(0.4, all.get(1).score(), EPS);
        assertEquals(0.4, all.get(2).score(), EPS);

        TestDatabase.exec(db, "INSERT INTO commonervote_promotions (player, promoted_at, source, welcomed) "
                + "VALUES ('" + R2 + "', 1, 'auto', TRUE)");
        List<RecipientScore> withoutPromoted = store.candidates(SINCE, 10);
        assertEquals(List.of(R1, R3, R4), withoutPromoted.stream().map(RecipientScore::recipient).toList(),
                "a promoted recipient is not a candidate");
        assertTrue(store.hasAnyVotes(R2), "but the promoted player's votes are still there");

        assertEquals(List.of(R1, R3), store.candidates(SINCE, 2).stream().map(RecipientScore::recipient).toList(),
                "the limit keeps the best");
        assertEquals(List.of(), store.candidates(SINCE, 0));
    }

    @Test
    void candidates_ties_ignore_the_noise_of_adding_fractions() throws SQLException {
        // 0.1 + 0.2 is 0.30000000000000004 as a double, one hair more than 0.3.
        insert(V1, R2, NOW, 0.1, "");
        insert(V2, R2, NOW, 0.2, "");
        insert(V1, R1, NOW, 0.3, "");

        List<RecipientScore> candidates = store.candidates(SINCE, 10);

        assertEquals(List.of(R1, R2), candidates.stream().map(RecipientScore::recipient).toList(),
                "the same score is a tie, so the lower UUID goes first");
        assertEquals(0.3, candidates.get(0).score(), EPS);
        assertEquals(0.3, candidates.get(1).score(), EPS);
    }

    @Test
    void candidates_of_nothing_are_empty() throws SQLException {
        assertEquals(List.of(), store.candidates(SINCE, 10));
        assertFalse(store.hasAnyVotes(R1));
    }

    // ---- helpers ----

    private static UUID uuid(long n) {
        return new UUID(0, n);
    }

    private static Set<UUID> voters(List<Vote> votes) {
        return votes.stream().map(Vote::voter).collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> reasons(List<Vote> votes) {
        return votes.stream().map(Vote::reason).toList();
    }

    /** A row written the way 1.2.9 wrote it, with no column list. */
    private void insert(UUID voter, UUID recipient, long timestamp, double weight, String reason)
            throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO commonervote_votes VALUES (?,?,?,?,?)")) {
            ps.setString(1, voter.toString());
            ps.setString(2, recipient.toString());
            ps.setLong(3, timestamp);
            ps.setDouble(4, weight);
            ps.setString(5, reason);
            ps.executeUpdate();
        }
    }

    private double weightOf(UUID voter, UUID recipient) throws SQLException {
        return (Double) singleValue("weight", voter, recipient);
    }

    private long timestampOf(UUID voter, UUID recipient) throws SQLException {
        return (Long) singleValue("timestamp", voter, recipient);
    }

    private String reasonOf(UUID voter, UUID recipient) throws SQLException {
        return (String) singleValue("reason", voter, recipient);
    }

    private Object singleValue(String column, UUID voter, UUID recipient) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT " + column
                     + " FROM commonervote_votes WHERE voter = ? AND recipient = ?")) {
            ps.setString(1, voter.toString());
            ps.setString(2, recipient.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no row for " + voter + " and " + recipient);
                Object value = switch (column) {
                    case "weight" -> rs.getDouble(1);
                    case "timestamp" -> rs.getLong(1);
                    default -> rs.getString(1);
                };
                assertNotNull(value, column + " must not be null");
                return value;
            }
        }
    }

    private int countRows(UUID voter, UUID recipient) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM commonervote_votes WHERE voter = ? AND recipient = ?")) {
            ps.setString(1, voter.toString());
            ps.setString(2, recipient.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int rowCount() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM commonervote_votes");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Connections that throw {@code failure} on the vote INSERT, and behave normally otherwise. */
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
}
