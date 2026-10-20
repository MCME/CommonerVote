package com.mcmiddleearth.commonervote.service;

import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerExpChangeEvent;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.domain.Settings;

/**
 * Uses the XP bar as a progress meter for guests, and for guests only.
 * <p>
 * The 2020 plugin zeroed every XP gain of every player on every server. Here a player is managed only when the
 * {@code xpBar} setting is on and the player lacks {@code commonervote.exempt}; everyone else gains XP normally. The
 * setting is read on each call, so a change saved in the settings dialog applies at once.
 * <p>
 * All methods run on the main thread (they touch a {@link Player}).
 */
public final class XpBarService {

    private final Supplier<Settings> settings;

    public XpBarService(Supplier<Settings> settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    /** Whether this player's XP bar is the plugin's progress meter: {@code xpBar} is on and the player is a guest. */
    public boolean manages(Player p) {
        return settings.get().xpBar() && !p.hasPermission(Permissions.EXEMPT);
    }

    /**
     * Shows {@code score} on a managed player's bar: level 0, and the bar filled to {@code score} (a score above 1
     * fills it, a score below 0 empties it). A player who is not managed is left alone.
     */
    public void refresh(Player p, double score) {
        if (!manages(p)) {
            return;
        }
        p.setLevel(0);
        p.setExp((float) Math.max(0.0, Math.min(score, 1.0)));
    }

    /**
     * Empties the bar, for a guest who has just become a Commoner. This does not check {@link #manages}: by then
     * the player is exempt, but the bar still shows the meter.
     */
    public void release(Player p) {
        p.setLevel(0);
        p.setExp(0.0f);
    }

    /** Swallows the XP a managed player picks up, so the meter only moves with votes. */
    public void onExpChange(PlayerExpChangeEvent e) {
        if (manages(e.getPlayer())) {
            e.setAmount(0);
        }
    }
}
