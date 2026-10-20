package com.mcmiddleearth.commonervote.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.OfflinePlayerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.mcmiddleearth.commonervote.service.PlayerNames.Target;
import com.mcmiddleearth.commonervote.support.FakeRankService;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where the names come from: memory and LuckPerms only, never a player's data file; and a typed UUID finds its player.
 */
class PlayerNamesTest {

    /** Counts the calls of {@code getOfflinePlayer(UUID)}, whose {@code getName()} reads the player's data file. */
    static final class CountingServer extends ServerMock {

        final AtomicInteger offlineLookups = new AtomicInteger();

        @Override
        public OfflinePlayer getOfflinePlayer(UUID id) {
            offlineLookups.incrementAndGet();
            return super.getOfflinePlayer(id);
        }
    }

    private CountingServer server;
    private final FakeRankService ranks = new FakeRankService();
    private final Map<UUID, String> cached = new HashMap<>();
    /** The players the profile cache was asked about, in order. */
    private final List<UUID> cacheLookups = new ArrayList<>();
    private PlayerNames names;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new CountingServer());
        names = new PlayerNames(ranks, player -> {
            cacheLookups.add(player);
            return Optional.ofNullable(cached.get(player));
        }, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void names_come_from_memory_then_luckperms_never_from_a_data_file() {
        PlayerMock online = server.addPlayer("Bilbo_B");
        UUID onceHere = UUID.randomUUID();
        cached.put(onceHere, "Frodo99");
        ranks.add(onceHere, "frodo99"); // LuckPerms keeps names in lowercase
        UUID remote = UUID.randomUUID();
        ranks.add(remote, "gimli");
        UUID stranger = UUID.randomUUID();
        // Each offline player has a data file here too, with another name in it, which must not be read.
        for (UUID id : List.of(onceHere, remote, stranger)) {
            server.getPlayerList().addOfflinePlayer(new OfflinePlayerMock(id, "OnDisk"));
        }
        server.offlineLookups.set(0);

        Map<UUID, String> shown = names.display(List.of(online.getUniqueId(), onceHere, remote, stranger),
                UUID::toString).join();

        assertEquals(Map.of(online.getUniqueId(), "Bilbo_B", onceHere, "Frodo99", remote, "gimli",
                stranger, stranger.toString()), shown);
        assertEquals(List.of(onceHere, remote, stranger), cacheLookups,
                "every player who is not online is looked up in the profile cache");
        assertEquals(0, server.offlineLookups.get(), "never getOfflinePlayer(id), whose name reads the data file");
    }

    @Test
    void a_found_name_is_shown_from_memory_too() {
        UUID onceHere = UUID.randomUUID();
        cached.put(onceHere, "Frodo99");
        ranks.add(onceHere, "frodo99");
        server.getPlayerList().addOfflinePlayer(new OfflinePlayerMock(onceHere, "OnDisk"));
        server.offlineLookups.set(0);

        assertEquals(Optional.of(new Target(onceHere, "Frodo99")), names.find("FRODO99").join());
        assertEquals(0, server.offlineLookups.get());
    }

    @Test
    void a_typed_uuid_finds_that_player() {
        UUID remote = UUID.randomUUID();
        ranks.add(remote, "gimli");
        UUID stranger = UUID.randomUUID();

        assertEquals(Optional.of(new Target(remote, "gimli")), names.find(remote.toString()).join());
        assertEquals(Optional.of(new Target(remote, "gimli")),
                names.find(remote.toString().toUpperCase(Locale.ROOT)).join());
        assertEquals(Optional.of(new Target(stranger, stranger.toString())), names.find(stranger.toString()).join(),
                "a player nobody can name is shown, and suggested, by UUID, so the UUID must find them");
    }

    @Test
    void anything_else_is_looked_up_as_a_name() {
        String notAUuid = "x".repeat(36);
        ranks.add(UUID.randomUUID(), notAUuid);

        assertEquals(Optional.empty(), names.find("not-a-uuid").join());
        assertEquals(notAUuid, names.find(notAUuid).join().orElseThrow().name());
    }
}
