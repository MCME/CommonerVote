package com.mcmiddleearth.commonervote.domain;

/**
 * The text boxes the settings dialog submits, applied with {@link Settings#withTexts}.
 * {@code promotableGroups} is the comma-separated text as typed.
 */
public record TextsForm(String commonerGroup, String promotableGroups, String promotionCommand, String rulesUrl,
                        String guideUrl, String guestInfoText, String welcomeText, String announceText) {
}
