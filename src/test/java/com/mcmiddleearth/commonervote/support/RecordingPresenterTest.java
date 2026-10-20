package com.mcmiddleearth.commonervote.support;

import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.ui.view.CandidateListView;
import com.mcmiddleearth.commonervote.ui.view.ConfirmView;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecordingPresenterTest {

    private static Player player() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        return p;
    }

    @Test
    void keeps_the_last_view_of_each_type_per_player() {
        RecordingPresenter presenter = new RecordingPresenter();
        Player frodo = player();
        Player sam = player();
        CandidateListView first = new CandidateListView(List.of());
        CandidateListView second = new CandidateListView(List.of());
        ConfirmView confirm = new ConfirmView("t", "q", "ok", p -> { });

        presenter.showCandidateList(frodo, first);
        presenter.showConfirm(frodo, confirm);
        presenter.showCandidateList(frodo, second);

        assertSame(second, presenter.last(frodo, CandidateListView.class));
        assertSame(confirm, presenter.last(frodo, ConfirmView.class));
        assertNull(presenter.last(sam, CandidateListView.class));
    }

    @Test
    void clear_forgets_everything() {
        RecordingPresenter presenter = new RecordingPresenter();
        Player frodo = player();
        presenter.showCandidateList(frodo, new CandidateListView(List.of()));

        presenter.clear();

        assertNull(presenter.last(frodo, CandidateListView.class));
    }
}
