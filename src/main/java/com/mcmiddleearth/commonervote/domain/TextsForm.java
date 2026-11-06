package com.mcmiddleearth.commonervote.domain;

/**
 * The text boxes the settings dialog submits, applied with {@link Settings#withTexts}.
 * {@code promotableGroups} is the comma-separated text as typed.
 */
public record TextsForm(String promotableGroups, String rulesUrl, String guideUrl, String guestInfoText,
                        String welcomeText, String announceText) {
}
