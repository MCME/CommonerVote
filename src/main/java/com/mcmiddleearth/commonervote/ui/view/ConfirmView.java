package com.mcmiddleearth.commonervote.ui.view;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * A yes-or-no question before something that cannot be undone.
 *
 * @param confirmLabel the text of the button that goes ahead
 * @param onConfirm    what that button does; cancelling needs no callback
 */
public record ConfirmView(String title, String question, String confirmLabel, Consumer<Player> onConfirm) {
}
