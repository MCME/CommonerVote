package com.mcmiddleearth.commonervote.support;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.ui.DialogPresenter;
import com.mcmiddleearth.commonervote.ui.view.CandidateListView;
import com.mcmiddleearth.commonervote.ui.view.ConfirmView;
import com.mcmiddleearth.commonervote.ui.view.GuestInfoView;
import com.mcmiddleearth.commonervote.ui.view.ReviewDetailView;
import com.mcmiddleearth.commonervote.ui.view.ReviewListView;
import com.mcmiddleearth.commonervote.ui.view.SettingsView;
import com.mcmiddleearth.commonervote.ui.view.TextsView;
import com.mcmiddleearth.commonervote.ui.view.VoteView;
import com.mcmiddleearth.commonervote.ui.view.WelcomeView;

/**
 * A {@link DialogPresenter} that draws nothing and remembers what it was asked to show. A test reads the view back
 * with {@link #last} and calls its callbacks, as a click would.
 * <p>
 * It keeps only the latest view of each type per player, found by the player's unique id. Showing a second view of
 * the same type replaces the first; a view of another type leaves it alone.
 */
public final class RecordingPresenter implements DialogPresenter {

    private final Map<UUID, Map<Class<?>, Object>> shown = new ConcurrentHashMap<>();

    /** The latest view of this type shown to the player since the last {@link #clear}, or {@code null}. */
    public <T> T last(Player p, Class<T> type) {
        Map<Class<?>, Object> views = shown.get(p.getUniqueId());
        return views == null ? null : type.cast(views.get(type));
    }

    /** Forgets every view shown so far. */
    public void clear() {
        shown.clear();
    }

    @Override
    public void showCandidateList(Player viewer, CandidateListView view) {
        record(viewer, view);
    }

    @Override
    public void showVote(Player viewer, VoteView view) {
        record(viewer, view);
    }

    @Override
    public void showGuestInfo(Player viewer, GuestInfoView view) {
        record(viewer, view);
    }

    @Override
    public void showWelcome(Player viewer, WelcomeView view) {
        record(viewer, view);
    }

    @Override
    public void showReviewList(Player viewer, ReviewListView view) {
        record(viewer, view);
    }

    @Override
    public void showReviewDetail(Player viewer, ReviewDetailView view) {
        record(viewer, view);
    }

    @Override
    public void showConfirm(Player viewer, ConfirmView view) {
        record(viewer, view);
    }

    @Override
    public void showSettings(Player viewer, SettingsView view) {
        record(viewer, view);
    }

    @Override
    public void showTexts(Player viewer, TextsView view) {
        record(viewer, view);
    }

    private void record(Player viewer, Object view) {
        shown.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>()).put(view.getClass(), view);
    }
}
