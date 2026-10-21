package com.mcmiddleearth.commonervote.domain;

import java.util.UUID;

/**
 * One row of the {@code commonervote_votes} table.
 *
 * @param timestamp when the vote was cast, in epoch milliseconds
 * @param weight    the vote's share of a promotion, normalised: {@code teamWeight / votesNeeded} for a team vote and
 *                  {@code staffWeight / votesNeeded} for a staff vote, so the weights of valid votes add up to the
 *                  recipient's score
 * @param reason    free text, {@code ""} when the voter gave none
 */
public record Vote(UUID voter, UUID recipient, long timestamp, double weight, String reason) {
}
