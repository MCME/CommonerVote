package com.mcmiddleearth.commonervote.ui.view;

import net.kyori.adventure.text.Component;

/**
 * What a guest sees on {@code /vote}: how the vote works and how far along they are.
 *
 * @param percent  the guest's own progress, 0 to 100
 * @param infoText the guest info text of the settings, already parsed
 * @param guideUrl a link to a building guide; empty when there is none
 */
public record GuestInfoView(int percent, Component infoText, String guideUrl) {
}
