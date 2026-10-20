package com.mcmiddleearth.commonervote.ui.view;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * The list of candidates a reviewer can open, with how far each one is.
 *
 * @param rows the candidates, in the order to show; immutable
 */
public record ReviewListView(List<Row> rows) {

    public ReviewListView {
        rows = List.copyOf(rows);
    }

    /**
     * One candidate.
     *
     * @param percent        the candidate's progress, 0 to 100
     * @param blockedByGroup the group that keeps the promotion from running by itself; empty when nothing does
     * @param onClick        what a click on the candidate does for the player who clicked
     */
    public record Row(UUID player, String name, int percent, Optional<String> blockedByGroup,
                      Consumer<Player> onClick) {
    }
}
