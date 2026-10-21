package com.mcmiddleearth.commonervote.listener;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.mcmiddleearth.commonervote.service.PromotionService;
import com.mcmiddleearth.commonervote.service.XpBarService;

/**
 * Hands the player events the plugin cares about to the services: a join and a quit to the promotion service, which
 * starts and ends the player's session there, and an XP pickup to the XP bar service.
 */
public final class PlayerListener implements Listener {

    private final PromotionService promotion;
    private final XpBarService xp;

    public PlayerListener(PromotionService promotion, XpBarService xp) {
        this.promotion = Objects.requireNonNull(promotion);
        this.xp = Objects.requireNonNull(xp);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        promotion.onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        promotion.onQuit(e.getPlayer());
    }

    @EventHandler
    public void onExpChange(PlayerExpChangeEvent e) {
        xp.onExpChange(e);
    }
}
