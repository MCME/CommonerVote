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
 * knows players who were only ever on another server. The name shown for a player is this server's own spelling when
 * the server knows the player ({@code Bukkit.getOfflinePlayer(id).getName()}), else the rank service's name, which
 * LuckPerms keeps in lowercase, else a last resort that the caller picks.
 */
final class PlayerNames {

    /** A player and the name to show for them. */
    record Target(UUID id, String name) {
    }

    private final RankService ranks;
    private final Executor main;

    /**
     * @param main runs work on the server's main thread
     */
    PlayerNames(RankService ranks, Executor main) {
        this.ranks = Objects.requireNonNull(ranks);
        this.main = Objects.requireNonNull(main);
    }

    /**
     * Finds the player a sender typed, whichever case it is typed in; empty when nobody of that name is known. Call
     * this on the main thread. The future may complete on any thread. The name of the target is this server's
     * spelling, else the rank service's, and as a last resort the name as it was typed (the player exists, since the
     * rank service found them).
     */
    CompletableFuture<Optional<Target>> find(String typedName) {
        Player online = Bukkit.getPlayerExact(typedName);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new Target(online.getUniqueId(), online.getName())));
        }
        return ranks.lookup(typedName).thenComposeAsync(match -> named(match, typedName), main);
    }

    private CompletableFuture<Optional<Target>> named(Optional<UUID> match, String typedName) {
        if (match.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        UUID id = match.get();
        return display(List.of(id), unknown -> typedName)
                .thenApply(names -> Optional.of(new Target(id, names.get(id))));
    }

    /**
     * The name to show for each of these players, in a map with a key for every one of them. Call this on the main
     * thread (it asks Bukkit); the future may complete on any thread.
     *
     * @param lastResort the name for a player that neither this server nor the rank service knows
     */
    CompletableFuture<Map<UUID, String>> display(Collection<UUID> ids, Function<UUID, String> lastResort) {
        Map<UUID, String> shown = new HashMap<>();
        List<UUID> unseen = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            String knownName = Bukkit.getOfflinePlayer(id).getName(); // null for a player this server never saw
            if (knownName != null) {
                shown.put(id, knownName);
            } else {
                unseen.add(id);
            }
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
}
