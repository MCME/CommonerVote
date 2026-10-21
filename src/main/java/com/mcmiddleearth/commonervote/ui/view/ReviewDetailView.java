package com.mcmiddleearth.commonervote.ui.view;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * Every valid vote for one candidate, with what a reviewer may do about it.
 *
 * @param name      the candidate's name as stored, in its own case
 * @param percent   the candidate's progress, 0 to 100
 * @param votes     the valid votes, in the order to show; immutable
 * @param onPromote promotes the candidate now; empty when the viewer may not
 * @param onClear   starts clearing all votes for the candidate; empty when the viewer may not
 * @param onBack    returns to the candidate list
 */
public record ReviewDetailView(String name, int percent, List<Line> votes, Optional<Consumer<Player>> onPromote,
                               Optional<Consumer<Player>> onClear, Consumer<Player> onBack) {

    public ReviewDetailView {
        votes = List.copyOf(votes);
    }

    /**
     * One vote.
     *
     * @param staff    whether the voter's vote counted as a staff vote
     * @param daysAgo  how long ago the vote was cast
     * @param daysLeft how many days the vote stays valid
     * @param reason   the reason given, or {@code ""}
     */
    public record Line(String voterName, boolean staff, int daysAgo, int daysLeft, String reason) {
    }
}
