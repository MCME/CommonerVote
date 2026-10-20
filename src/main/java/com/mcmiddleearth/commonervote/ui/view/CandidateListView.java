package com.mcmiddleearth.commonervote.ui.view;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * The list of guests who have valid votes, shown to a voter.
 *
 * @param rows the candidates, in the order to show; immutable
 */
public record CandidateListView(List<Row> rows) {

    public CandidateListView {
        rows = List.copyOf(rows);
    }

    /**
     * One candidate.
     *
     * @param percent       the candidate's progress, 0 to 100
     * @param votedByViewer whether the viewer has a valid vote for this candidate
     * @param onClick       what a click on the candidate does for the player who clicked
     */
    public record Row(UUID player, String name, int percent, boolean votedByViewer, Consumer<Player> onClick) {
    }
}
