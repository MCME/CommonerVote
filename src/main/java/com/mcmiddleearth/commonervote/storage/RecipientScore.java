package com.mcmiddleearth.commonervote.storage;

import java.util.UUID;

/**
 * A recipient and the sum of the weights of their valid votes: one line of the review list.
 *
 * @param score the sum of the weights, where {@code 1.0} is a whole promotion
 */
public record RecipientScore(UUID recipient, double score) {
}
