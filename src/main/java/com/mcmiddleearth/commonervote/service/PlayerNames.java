package com.mcmiddleearth.commonervote.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.mcmiddleearth.commonervote.rank.RankService;

/**
 * How the services turn a typed name into a player, and a player into a name to show.
 * <p>
 * A typed name is found among the players online here (in any case), then through the {@link RankService}, which also
 * knows players who were only ever on another server. A typed UUID is that player: a player that nobody can name is
 * shown, and suggested, by their UUID.
 * <p>
 * The name shown for a player comes from memory only: the online player's name, else {@link ProfileCache Paper's
 * profile cache}, else the rank service's name, which LuckPerms keeps in lowercase, else a last resort that the caller
 * picks. Never {@code Bukkit.getOfflinePlayer(id).getName()}: for an offline player that reads the player's data file
 * from disk, on the main thread, for every name in a review list and on every keystroke of a tab completion.
 */
final class PlayerNames {

    /** The length of a UUID written out, such as {@code 3f8e1c2a-5b7d-4e9f-a1c3-2d4b6f8a0e1c}. */
    private static final int UUID_LENGTH = 36;

    /** A player and the name to show for them. */
    record Target(UUID id, String name) {
    }

    private final RankService ranks;
    private final ProfileCache profiles;
    private final Executor main;

    /**
     * @param main runs work on the server's main thread
     */
    PlayerNames(RankService ranks, ProfileCache profiles, Executor main) {
        this.ranks = Objects.requireNonNull(ranks);
        this.profiles = Objects.requireNonNull(profiles);
        this.main = Objects.requireNonNull(main);
    }

    /**
     * Finds the player a sender typed, whichever case it is typed in; empty when nobody of that name is known. A typed
     * UUID always finds that player, as {@link #display} names them. Call this on the main thread. The future may
     * complete on any thread. The name of a target found by name is the one {@link #display} shows, and as a last
     * resort the name as it was typed (the player exists, since the rank service found them).
     */
    CompletableFuture<Optional<Target>> find(String typed) {
        Optional<UUID> typedId = asUuid(typed);
        if (typedId.isPresent()) {
            return named(typedId, UUID::toString);
        }
        Player online = Bukkit.getPlayerExact(typed);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new Target(online.getUniqueId(), online.getName())));
        }
        return ranks.lookup(typed).thenComposeAsync(match -> named(match, unknown -> typed), main);
    }

    /** Runs on the main thread. */
    private CompletableFuture<Optional<Target>> named(Optional<UUID> match, Function<UUID, String> lastResort) {
        if (match.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        UUID id = match.get();
        return display(List.of(id), lastResort).thenApply(names -> Optional.of(new Target(id, names.get(id))));
    }

    /**
     * The name to show for each of these players, in a map with a key for every one of them. Call this on the main
     * thread (it asks Bukkit and the profile cache); the future may complete on any thread.
     *
     * @param lastResort the name for a player whom neither this server nor the rank service can name
     */
    CompletableFuture<Map<UUID, String>> display(Collection<UUID> ids, Function<UUID, String> lastResort) {
        Map<UUID, String> shown = new HashMap<>();
        List<UUID> unseen = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            inMemory(id).ifPresentOrElse(name -> shown.put(id, name), () -> unseen.add(id));
        }
        if (unseen.isEmpty()) {
            return CompletableFuture.completedFuture(shown);
        }
        return ranks.names(unseen).thenApply(names -> {
            Map<UUID, String> all = new HashMap<>(shown);
            for (UUID id : unseen) {
                all.put(id, names.getOrDefault(id, lastResort.apply(id)));
            }
            return all;
        });
    }

    /** The name this server holds in memory for the player: online here, or in the profile cache. */
    private Optional<String> inMemory(UUID id) {
        Player online = Bukkit.getPlayer(id);
        return online != null ? Optional.of(online.getName()) : profiles.name(id);
    }

    /** The UUID that {@code typed} spells out, if it is one; a name never has 36 characters. */
    private static Optional<UUID> asUuid(String typed) {
        if (typed.length() != UUID_LENGTH) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(typed));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
