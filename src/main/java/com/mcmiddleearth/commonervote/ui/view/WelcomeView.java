package com.mcmiddleearth.commonervote.ui.view;

import net.kyori.adventure.text.Component;

/**
 * The welcome shown to a player who has just become a Commoner.
 *
 * @param welcomeText the welcome text of the settings, already parsed
 * @param rulesUrl    the link to the rules
 */
public record WelcomeView(Component welcomeText, String rulesUrl) {
}
