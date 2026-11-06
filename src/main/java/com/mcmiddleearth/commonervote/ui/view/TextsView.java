package com.mcmiddleearth.commonervote.ui.view;

import java.util.function.BiConsumer;

import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.TextsForm;

/**
 * The dialog for the text settings: the groups that may be promoted, links and the three messages.
 *
 * @param current the settings to fill the text boxes with
 * @param onSave  saves the submitted texts
 */
public record TextsView(Settings current, BiConsumer<Player, TextsForm> onSave) {
}
