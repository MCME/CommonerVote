package com.mcmiddleearth.commonervote.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.Vote;
import com.mcmiddleearth.commonervote.domain.VoteMath;

/**
 * Reads and writes the {@code commonervote_votes} table, which is 1.2.9's table, unchanged: the 2020 plugin may still
 * be running against the same database, so every row written here must be readable by 1.2.9's SQL and the other way
 * round.
 * <p>
 * Every method opens its own connection and closes it again. Voting is one transaction. Only plain SQL that both
 * MariaDB and the H2 of the tests understand is used. Rows are never removed because they expired; they stay as
 * history, and the readers take the {@code validSince} limit instead.
 * <p>
 * Voters and recipients are stored as the 36-character form of their UUID, as 1.2.9 wrote them. A weight is stored
 * normalised, as the vote's share of a promotion (see {@link Vote}).
 */
public final class VoteStore {

    /** The width of the {@code reason} column. */
    private static final int REASON_LENGTH = 150;

    private static final String COLUMNS = "voter, recipient, timestamp, weight, reason";

    /** Scores are compared to nine decimals; see {@link #candidates}. */
    private static final double SCORE_PRECISION = 1e9;

    private final ConnectionFactory factory;

    public VoteStore(ConnectionFactory factory) {
        this.factory = factory;
    }

    /**
     * What a cast did.
     *
     * @param vote               the row as it is stored now
     * @param scoreBefore        the recipient's score before the cast, from the valid votes
     * @param scoreAfter         the recipient's score after it
     * @param replacedValidVote  whether the voter already had a valid vote for this recipient
     */
    public record CastResult(Vote vote, double scoreBefore, double scoreAfter, boolean replacedValidVote) {
    }

    /**
     * Records {@code voter}'s vote for {@code recipient}, replacing the one they had. A recast never lowers the
     * weight: the vote keeps the highest weight of the voter's earlier valid vote for this recipient (1.2.9's rule).
     * An expired vote is ignored: it is replaced, but it lends its weight to nothing and is not
     * {@code replacedValidVote}.
     * <p>
     * One transaction, so a failure leaves the voter's previous vote exactly as it was. The three weight settings
     * are read in it with a row lock ({@link SettingsStore#lockWeightSettings}, the same first statement a settings
     * save runs), so a vote is never written with a weight scaled by a {@code votesNeeded} that another backend is
     * just changing, and casts do not overlap. A setting that is missing or unusable counts as its default, as
     * {@link Settings#fromMap} reads it.
     *
     * @param reason     free text; {@code null} or blank is stored as {@code ""}, and a long text is cut to what the
     *                   column holds
     * @param validSince the oldest timestamp that still counts, see {@link VoteMath#validSince}
     */
    public CastResult cast(UUID voter, UUID recipient, boolean staff, String reason, long now, long validSince)
            throws SQLException {
        String storedReason = cleanReason(reason);
        return Transactions.run(factory, c -> {
            Settings settings = SettingsStore.lockWeightSettings(c);
            double wanted = VoteMath.weightFor(staff, settings.staffWeight(), settings.teamWeight(),
                    settings.votesNeeded());

            // What the voter has now. Only a valid vote counts: an expired row is history, and is replaced without
            // lending its weight to the new vote. More than one row can exist for a pair, if something else wrote
            // them; all of them are replaced, and the highest valid weight survives.
            double highestValid = 0;
            double previousValid = 0;
            boolean replacedValid = false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT weight, timestamp FROM commonervote_votes WHERE voter = ? AND recipient = ?")) {
                ps.setString(1, voter.toString());
                ps.setString(2, recipient.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double rowWeight = rs.getDouble(1);
                        long rowTimestamp = rs.getLong(2);
                        if (rowTimestamp >= validSince) { // as VoteMath.isValid
                            highestValid = Math.max(highestValid, rowWeight);
                            previousValid += rowWeight;
                            replacedValid = true;
                        }
                    }
                }
            }

            double scoreBefore = VoteMath.score(selectValid(c, List.of(recipient), validSince), validSince);
            double weight = Math.max(highestValid, wanted);

            delete(c, "DELETE FROM commonervote_votes WHERE voter = ? AND recipient = ?", voter, recipient);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO commonervote_votes (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, voter.toString());
                ps.setString(2, recipient.toString());
                ps.setLong(3, now);
                ps.setDouble(4, weight);
                ps.setString(5, storedReason);
                ps.executeUpdate();
            }

            return new CastResult(new Vote(voter, recipient, now, weight, storedReason), scoreBefore,
                    scoreBefore - previousValid + weight, replacedValid);
        });
    }

    /** Deletes {@code voter}'s vote for {@code recipient}, valid or not; true if there was one. */
    public boolean withdraw(UUID voter, UUID recipient) throws SQLException {
        try (Connection c = factory.open()) {
            return delete(c, "DELETE FROM commonervote_votes WHERE voter = ? AND recipient = ?", voter, recipient) > 0;
        }
    }

    /** Deletes every vote for {@code recipient}, valid or not; the number of rows deleted. */
    public int clear(UUID recipient) throws SQLException {
        try (Connection c = factory.open()) {
            return delete(c, "DELETE FROM commonervote_votes WHERE recipient = ?", recipient);
        }
    }

    /** The votes for {@code recipient} that are valid at {@code validSince}, newest first. */
    public List<Vote> validVotesFor(UUID recipient, long validSince) throws SQLException {
        try (Connection c = factory.open()) {
            return selectValid(c, List.of(recipient), validSince);
        }
    }

    /**
     * The valid votes of each of {@code recipients}, newest first. Every recipient asked for is a key of the result,
     * with an empty list if they have no valid votes. An empty request is answered without using the database.
     */
    public Map<UUID, List<Vote>> validVotesFor(Collection<UUID> recipients, long validSince) throws SQLException {
        Set<UUID> asked = new LinkedHashSet<>(recipients);
        if (asked.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Vote>> result = new LinkedHashMap<>();
        for (UUID recipient : asked) {
            result.put(recipient, new ArrayList<>());
        }
        try (Connection c = factory.open()) {
            for (Vote vote : selectValid(c, asked, validSince)) {
                result.get(vote.recipient()).add(vote);
            }
        }
        result.replaceAll((recipient, votes) -> List.copyOf(votes));
        return result;
    }

    /**
     * The recipients that have a score above 0 from valid votes and have not been promoted (no row in
     * {@code commonervote_promotions}): highest score first, ties by the recipient's UUID text, at most {@code limit}.
     * <p>
     * The database only filters; the sums and the order are made here. Adding fractions leaves noise (0.1 + 0.2 is a
     * hair more than 0.3, and a database may add in either order), so a score is rounded to nine decimals before
     * scores are compared, and equal scores always tie. The order of the UUID text is the plain character order,
     * whatever collation the database has.
     */
    public List<RecipientScore> candidates(long validSince, int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, Double> sums = new HashMap<>();
        try (Connection c = factory.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT v.recipient, v.weight FROM commonervote_votes v "
                             + "LEFT JOIN commonervote_promotions p ON p.player = v.recipient "
                             + "WHERE v.timestamp >= ? AND p.player IS NULL")) {
            ps.setLong(1, validSince);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sums.merge(rs.getString(1), rs.getDouble(2), Double::sum);
                }
            }
        }
        List<RecipientScore> scores = new ArrayList<>();
        for (Map.Entry<String, Double> sum : sums.entrySet()) {
            double score = Math.rint(sum.getValue() * SCORE_PRECISION) / SCORE_PRECISION;
            if (score > 0) {
                scores.add(new RecipientScore(UUID.fromString(sum.getKey()), score));
            }
        }
        scores.sort(Comparator.comparingDouble(RecipientScore::score).reversed()
                .thenComparing(s -> s.recipient().toString()));
        return List.copyOf(scores.subList(0, Math.min(limit, scores.size())));
    }


    /** Whether {@code recipient} has any vote at all, expired ones included. */
    public boolean hasAnyVotes(UUID recipient) throws SQLException {
        try (Connection c = factory.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM commonervote_votes WHERE recipient = ? LIMIT 1")) {
            ps.setString(1, recipient.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The valid votes of the given recipients, newest first. */
    private static List<Vote> selectValid(Connection c, Collection<UUID> recipients, long validSince)
            throws SQLException {
        String marks = String.join(", ", Collections.nCopies(recipients.size(), "?"));
        List<Vote> votes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM commonervote_votes "
                + "WHERE recipient IN (" + marks + ") AND timestamp >= ? ORDER BY timestamp DESC, voter ASC")) {
            int index = 1;
            for (UUID recipient : recipients) {
                ps.setString(index++, recipient.toString());
            }
            ps.setLong(index, validSince);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String reason = rs.getString(5);
                    votes.add(new Vote(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                            rs.getLong(3), rs.getDouble(4), reason == null ? "" : reason));
                }
            }
        }
        return votes;
    }

    /** Runs a DELETE whose parameters are all UUIDs; the number of rows deleted. */
    private static int delete(Connection c, String sql, UUID... keys) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < keys.length; i++) {
                ps.setString(i + 1, keys[i].toString());
            }
            return ps.executeUpdate();
        }
    }

    /**
     * The reason as stored: {@code ""} for none, otherwise trimmed and cut to the first 150 characters (the width of
     * the column, which a database may count in either UTF-16 units or code points, so this fits both). A character
     * outside the basic plane is two units in Java, and is dropped whole rather than cut in half.
     */
    private static String cleanReason(String reason) {
        if (reason == null) {
            return "";
        }
        String trimmed = reason.trim();
        if (trimmed.length() <= REASON_LENGTH) {
            return trimmed;
        }
        int end = REASON_LENGTH;
        if (Character.isHighSurrogate(trimmed.charAt(end - 1))) {
            end--;
        }
        return trimmed.substring(0, end).trim();
    }
}
