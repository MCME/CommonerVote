package com.mcmiddleearth.commonervote.ui.view;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.domain.SettingsForm;

/**
 * The dialog for the numbers and switches of the shared settings.
 *
 * @param current the settings to fill the inputs with
 * @param onTexts opens the dialog for the text settings
 * @param onSave  saves the submitted numbers and switches
 */
public record SettingsView(Settings current, Consumer<Player> onTexts, BiConsumer<Player, SettingsForm> onSave) {
}
