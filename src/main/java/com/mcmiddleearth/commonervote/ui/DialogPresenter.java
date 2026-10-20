package com.mcmiddleearth.commonervote.ui;

import org.bukkit.entity.Player;

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
 * Shows a screen to a player. The services decide what a screen says and what its buttons do, and hand that over as a
 * view; a presenter only draws it. The Paper dialog implementation comes later, and tests use a recording one.
 * <p>
 * All methods are called on the main thread.
 */
public interface DialogPresenter {

    void showCandidateList(Player viewer, CandidateListView view);

    void showVote(Player viewer, VoteView view);

    void showGuestInfo(Player viewer, GuestInfoView view);

    void showWelcome(Player viewer, WelcomeView view);

    void showReviewList(Player viewer, ReviewListView view);

    void showReviewDetail(Player viewer, ReviewDetailView view);

    void showConfirm(Player viewer, ConfirmView view);

    void showSettings(Player viewer, SettingsView view);

    void showTexts(Player viewer, TextsView view);
}
