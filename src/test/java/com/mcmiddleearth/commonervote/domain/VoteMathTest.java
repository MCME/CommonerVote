package com.mcmiddleearth.commonervote.domain;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoteMathTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_800_000_000_000L;

    private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static Vote vote(long timestamp, double weight) {
        return new Vote(UUID.randomUUID(), RECIPIENT, timestamp, weight, "");
    }

    @Test
    void valid_since_counts_back_in_whole_days() {
        assertEquals(NOW - 100 * DAY, VoteMath.validSince(NOW, 100));
        assertEquals(NOW - DAY, VoteMath.validSince(NOW, 1));
        // 365 days is 31.5 billion ms, which overflows an int: the arithmetic has to be long.
        assertEquals(NOW - 365 * DAY, VoteMath.validSince(NOW, 365));
    }

    @Test
    void valid_boundary() {
        long since = VoteMath.validSince(NOW, 100);
        assertTrue(VoteMath.isValid(vote(since, 0.2), since));
        assertTrue(VoteMath.isValid(vote(since + 1, 0.2), since));
        assertFalse(VoteMath.isValid(vote(since - 1, 0.2), since));
    }

    @Test
    void score_sums_valid_only() {
        long since = VoteMath.validSince(NOW, 100);
        List<Vote> votes = List.of(vote(NOW - 10 * DAY, 0.2), vote(NOW - 50 * DAY, 0.4),
                vote(since - 1, 0.4));
        assertEquals(0.6, VoteMath.score(votes, since), 1e-12);
    }

    @Test
    void score_of_nothing_is_zero() {
        assertEquals(0.0, VoteMath.score(List.of(), VoteMath.validSince(NOW, 100)), 0.0);
    }

    @Test
    void promotion_threshold() {
        assertTrue(VoteMath.reachesPromotion(0.9999));
        assertTrue(VoteMath.reachesPromotion(1.0));
        assertTrue(VoteMath.reachesPromotion(1.4));
        assertFalse(VoteMath.reachesPromotion(0.99989));
        assertFalse(VoteMath.reachesPromotion(0.8));
    }

    @Test
    void five_team_votes_reach_promotion_despite_float_sums() {
        long since = VoteMath.validSince(NOW, 100);
        double w = VoteMath.weightFor(false, 2, 1, 7);
        List<Vote> votes = List.of(vote(NOW, w), vote(NOW, w), vote(NOW, w), vote(NOW, w), vote(NOW, w),
                vote(NOW, w), vote(NOW, w));
        assertTrue(VoteMath.reachesPromotion(VoteMath.score(votes, since)));
    }

    @Test
    void percent_rounding() {
        assertEquals(100, VoteMath.percent(0.99995));
        assertEquals(60, VoteMath.percent(0.6000000001));
        assertEquals(100, VoteMath.percent(1.4));
        assertEquals(0, VoteMath.percent(0));
        assertEquals(40, VoteMath.percent(0.4));
    }

    @Test
    void points() {
        assertEquals(4, VoteMath.points(0.8, 5));
        assertEquals(0, VoteMath.points(0, 5));
        assertEquals(5, VoteMath.points(1.0, 5));
        // a float sum a hair off the exact value still reads as the same number of points
        assertEquals(3, VoteMath.points(0.6000000000000001, 5));
        assertEquals(3, VoteMath.points(0.5999999999999999, 5));
    }

    @Test
    void weights() {
        assertEquals(0.4, VoteMath.weightFor(true, 2, 1, 5), 1e-12);
        assertEquals(0.2, VoteMath.weightFor(false, 2, 1, 5), 1e-12);
        assertEquals(1.0, VoteMath.weightFor(false, 1, 1, 1), 1e-12);
        assertEquals(1.0 / 3, VoteMath.weightFor(false, 2, 1, 3), 1e-12);
    }

    @Test
    void rescale_round_trip() {
        assertEquals(0.2, VoteMath.rescale(VoteMath.rescale(0.2, 5, 3), 3, 5), 1e-9);
        assertEquals(0.1, VoteMath.rescale(0.2, 5, 10), 1e-12);
        assertEquals(0.4, VoteMath.rescale(VoteMath.rescale(0.4, 5, 7), 7, 5), 1e-9);
        assertEquals(0.2, VoteMath.rescale(0.2, 5, 5), 1e-12);
    }

    @Test
    void staff_detection() {
        assertTrue(VoteMath.isStaffWeight(0.4, 2, 5));
        assertFalse(VoteMath.isStaffWeight(0.2, 2, 5));
        // a staff weight that went through a rescale round trip is still recognised
        assertTrue(VoteMath.isStaffWeight(VoteMath.rescale(VoteMath.rescale(0.4, 5, 3), 3, 5), 2, 5));
    }

    @Test
    void days() {
        long timestamp = NOW - 12 * DAY - DAY / 2; // 12.5 days ago
        assertEquals(12, VoteMath.daysAgo(timestamp, NOW));
        assertEquals(88, VoteMath.daysLeft(timestamp, NOW, 100));
    }

    @Test
    void days_left_never_goes_below_zero() {
        long timestamp = NOW - 130 * DAY;
        assertEquals(130, VoteMath.daysAgo(timestamp, NOW));
        assertEquals(0, VoteMath.daysLeft(timestamp, NOW, 100));
    }

    @Test
    void days_for_a_vote_cast_just_now_or_slightly_in_the_future() {
        assertEquals(0, VoteMath.daysAgo(NOW, NOW));
        assertEquals(100, VoteMath.daysLeft(NOW, NOW, 100));
        // another server's clock may run a little ahead: never "-1 days ago", never more than the full validity
        assertEquals(0, VoteMath.daysAgo(NOW + 5_000, NOW));
        assertEquals(100, VoteMath.daysLeft(NOW + 5_000, NOW, 100));
    }
}
