package com.mcmiddleearth.commonervote.storage;

import java.util.UUID;

/**
 * One row of the {@code commonervote_promotions} table: a player who is a Commoner, and how they became one.
 *
 * @param promotedAt when they were promoted (or, for {@link Source#DETECTED}, first noticed), in epoch milliseconds
 * @param source     how the record came about
 * @param welcomed   whether the player has seen the welcome dialog
 */
public record PromotionRecord(UUID player, long promotedAt, Source source, boolean welcomed) {

    /** How a player was promoted. Stored as the lower-case name. */
    public enum Source {
        /** The vote count reached the threshold and the plugin promoted them. */
        AUTO,
        /** A staff member promoted them. */
        MANUAL,
        /** They were found to be Commoners already (promoted outside the plugin) and have votes on record. */
        DETECTED
    }
}
