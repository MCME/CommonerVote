package com.mcmiddleearth.commonervote.service;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import com.destroystokyo.paper.profile.PlayerProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paper's profile cache, on mocks: MockBukkit cannot complete a profile from a cache.
 */
class ProfileCacheTest {

    private final Server server = mock(Server.class);
    private final PlayerProfile profile = mock(PlayerProfile.class);
    private final UUID player = UUID.randomUUID();

    @Test
    void the_name_comes_from_the_cache_only() {
        when(server.createProfile(player)).thenReturn(profile);
        when(profile.getName()).thenReturn("Bilbo_B");

        assertEquals(Optional.of("Bilbo_B"), ProfileCache.of(server).name(player));

        verify(profile).completeFromCache();
        verify(profile, never()).complete();
        verify(profile, never()).complete(anyBoolean());
        verify(profile, never()).complete(anyBoolean(), anyBoolean());
        verify(server, never()).getOfflinePlayer(player);
    }

    @Test
    void a_player_the_cache_does_not_know_has_no_name() {
        when(server.createProfile(player)).thenReturn(profile);
        when(profile.getName()).thenReturn(null);

        assertEquals(Optional.empty(), ProfileCache.of(server).name(player));

        when(profile.getName()).thenReturn("");

        assertEquals(Optional.empty(), ProfileCache.of(server).name(player));
    }
}
