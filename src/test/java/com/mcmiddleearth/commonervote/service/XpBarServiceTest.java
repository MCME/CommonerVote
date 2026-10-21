package com.mcmiddleearth.commonervote.service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.mcmiddleearth.commonervote.Permissions;
import com.mcmiddleearth.commonervote.domain.Settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XpBarServiceTest {

    private ServerMock server;
    private Plugin plugin;
    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.defaults());
    private XpBarService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        service = new XpBarService(settings::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock guest() {
        return server.addPlayer();
    }

    private PlayerMock exempt() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, Permissions.EXEMPT, true);
        return player;
    }

    @Test
    void guest_bar() {
        PlayerMock player = guest();
        player.setLevel(4);

        service.refresh(player, 0.6);

        assertEquals(0, player.getLevel());
        assertEquals(0.6f, player.getExp());

        service.refresh(player, 1.3);

        assertEquals(1.0f, player.getExp());
    }

    @Test
    void guest_bar_never_goes_below_empty() {
        PlayerMock player = guest();
        player.setExp(0.5f);

        service.refresh(player, -0.2);

        assertEquals(0.0f, player.getExp());
    }

    @Test
    void exempt_untouched() {
        PlayerMock player = exempt();
        player.setLevel(7);
        player.setExp(0.3f);

        service.refresh(player, 0.6);

        assertEquals(7, player.getLevel());
        assertEquals(0.3f, player.getExp());

        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 5);
        service.onExpChange(event);

        assertEquals(5, event.getAmount());
    }

    @Test
    void guest_orbs_cancelled() {
        PlayerMock player = guest();

        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 5);
        service.onExpChange(event);

        assertEquals(0, event.getAmount());
    }

    @Test
    void switch_off() {
        settings.set(Settings.fromMap(Map.of("xpBar", "false"), warning -> { }));
        PlayerMock player = guest();
        player.setLevel(7);
        player.setExp(0.3f);

        assertFalse(service.manages(player));

        service.refresh(player, 0.6);

        assertEquals(7, player.getLevel());
        assertEquals(0.3f, player.getExp());

        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 5);
        service.onExpChange(event);

        assertEquals(5, event.getAmount());
    }

    @Test
    void manages_follows_the_setting_at_call_time() {
        PlayerMock player = guest();
        assertTrue(service.manages(player));
        assertFalse(service.manages(exempt()));

        settings.set(Settings.fromMap(Map.of("xpBar", "false"), warning -> { }));

        assertFalse(service.manages(player));
    }

    @Test
    void release() {
        PlayerMock player = guest();
        service.refresh(player, 0.6);

        service.release(player);

        assertEquals(0, player.getLevel());
        assertEquals(0.0f, player.getExp());
    }

    @Test
    void release_also_empties_a_player_who_is_exempt_by_now() {
        PlayerMock player = guest();
        service.refresh(player, 1.0);
        player.addAttachment(plugin, Permissions.EXEMPT, true);

        service.release(player);

        assertEquals(0, player.getLevel());
        assertEquals(0.0f, player.getExp());
    }
}
