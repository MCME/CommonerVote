package com.mcmiddleearth.commonervote.ui.view;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * The dialog in which a voter votes for one guest, renews a vote or withdraws it.
 *
 * @param name         the candidate's name as stored, in its own case
 * @param percent      the candidate's progress, 0 to 100
 * @param points       the candidate's votes so far, out of {@code votesNeeded}
 * @param staffWeight  what a staff vote counts as, in votes
 * @param teamWeight   what a team vote counts as, in votes
 * @param votedDaysAgo how long ago the viewer's valid vote was cast; empty without one
 * @param reason       the reason of the viewer's valid vote, or {@code ""}; it fills the text box
 * @param onCast       casts or renews the vote; the string is the reason as typed
 * @param onWithdraw   withdraws the vote; empty when the viewer has no valid vote
 */
public record VoteView(String name, int percent, int points, int votesNeeded, int validityDays, int staffWeight,
                       int teamWeight, OptionalInt votedDaysAgo, String reason, BiConsumer<Player, String> onCast,
                       Optional<Consumer<Player>> onWithdraw) {
}
