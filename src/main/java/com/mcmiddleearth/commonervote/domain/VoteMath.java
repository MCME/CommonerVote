package com.mcmiddleearth.commonervote.domain;

import java.util.Collection;

/**
 * The arithmetic of votes: which ones still count, what they add up to, and how that is shown.
 * <p>
 * Pure functions on plain numbers, with no Bukkit and no database code. Every time is epoch milliseconds in a
 * {@code long}. A score is the sum of the weights of the valid votes, where a weight is the vote's share of a
 * promotion ({@code 1.0} promotes).
 */
public final class VoteMath {

    private static final long DAY_MILLIS = 86_400_000L;

    /** Scores are sums of floats, so "one whole promotion" is anything within this of 1.0. */
    private static final double PROMOTION_SCORE = 0.9999;

    /** How far apart two weights may be and still be the same stored weight. */
    private static final double WEIGHT_TOLERANCE = 1e-9;

    private VoteMath() {
    }

    /** The oldest timestamp that still counts: votes older than {@code validityDays} before {@code now} have expired. */
    public static long validSince(long now, int validityDays) {
        return now - validityDays * DAY_MILLIS;
    }

    /** A vote counts from the instant {@code since} on, so one cast exactly at {@code since} is still valid. */
    public static boolean isValid(Vote v, long since) {
        return v.timestamp() >= since;
    }

    /** The sum of the weights of the votes that are still valid. */
    public static double score(Collection<Vote> votes, long since) {
        double sum = 0;
        for (Vote v : votes) {
            if (isValid(v, since)) {
                sum += v.weight();
            }
        }
        return sum;
    }

    /** Whether a score is a whole promotion, with room for the rounding error of adding fractions. */
    public static boolean reachesPromotion(double score) {
        return score >= PROMOTION_SCORE;
    }

    /** The score as a percentage for display: rounded to the nearest whole percent and never above 100. */
    public static int percent(double score) {
        return (int) Math.min(100, Math.round(score * 100));
    }

    /** The score in votes, for display next to {@code votesNeeded}, rounded to the nearest whole vote. */
    public static int points(double score, int votesNeeded) {
        return (int) Math.round(score * votesNeeded);
    }

    /** The normalised weight of a new vote: what the voter's role is worth, as a share of {@code votesNeeded}. */
    public static double weightFor(boolean staff, int staffWeight, int teamWeight, int votesNeeded) {
        return (double) (staff ? staffWeight : teamWeight) / votesNeeded;
    }

    /** A stored weight converted to a new {@code votesNeeded}, so the vote keeps the same worth in whole votes. */
    public static double rescale(double weight, int oldNeeded, int newNeeded) {
        return weight * oldNeeded / newNeeded;
    }

    /** Whether a stored weight is what a staff vote is worth under these settings. */
    public static boolean isStaffWeight(double weight, int staffWeight, int votesNeeded) {
        return Math.abs(weight - (double) staffWeight / votesNeeded) < WEIGHT_TOLERANCE;
    }

    /**
     * How many whole days ago a vote was cast. A timestamp in the future (another server's clock running ahead)
     * counts as today, so it never reads "-1 days ago".
     */
    public static int daysAgo(long timestamp, long now) {
        return (int) Math.max(0, Math.floorDiv(now - timestamp, DAY_MILLIS));
    }

    /** How many days a vote still counts for, never below 0. */
    public static int daysLeft(long timestamp, long now, int validityDays) {
        return Math.max(0, validityDays - daysAgo(timestamp, now));
    }
}
