package com.mcmiddleearth.commonervote.domain;

/**
 * The numbers and switches the settings dialog submits, applied with {@link Settings#withNumbers}.
 */
public record SettingsForm(int votesNeeded, int validityDays, int staffWeight, int teamWeight, boolean autoPromote,
                           boolean xpBar, boolean broadcastNetwork) {
}
