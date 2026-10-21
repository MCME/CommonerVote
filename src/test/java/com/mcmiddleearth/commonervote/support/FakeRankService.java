package com.mcmiddleearth.commonervote.support;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.mcmiddleearth.commonervote.rank.RankService;

/**
 * A {@link RankService} that a test fills by hand. Every future is already completed, so a service under test needs
 * no waiting.
 * <p>
 * A player the test never {@link #add added} is unknown to it, as to the real LuckPerms: a lookup finds nothing and
 * {@link #names} leaves the player out. A player without groups set is in the {@code default} group only. Names are
 * found in any case, and group names are stored lowercase, as the real service returns them.
 */
public final class FakeRankService implements RankService {

    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private final Map<UUID, String> nameOf = new ConcurrentHashMap<>();
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<String>> groups = new ConcurrentHashMap<>();

    /** Makes the player known under this name. Adding the same player again renames it. */
    public FakeRankService add(UUID player, String name) {
        String previous = nameOf.put(player, name);
        if (previous != null) {
            byName.remove(key(previous));
        }
        byName.put(key(name), player);
        return this;
    }

    public FakeRankService setExempt(UUID player, boolean exempt) {
        if (exempt) {
            this.exempt.add(player);
        } else {
            this.exempt.remove(player);
        }
        return this;
    }

    /** Replaces the player's direct groups. Without arguments the player has no group at all. */
    public FakeRankService setGroups(UUID player, String... groupNames) {
        groups.put(player, Arrays.stream(groupNames)
                .map(FakeRankService::key)
                .collect(Collectors.toUnmodifiableSet()));
        return this;
    }

    @Override
    public CompletableFuture<Optional<UUID>> lookup(String name) {
        return CompletableFuture.completedFuture(Optional.ofNullable(byName.get(key(name))));
    }

    @Override
    public CompletableFuture<Map<UUID, String>> names(Collection<UUID> players) {
        Map<UUID, String> found = new LinkedHashMap<>();
        for (UUID player : players) {
            String name = nameOf.get(player);
            if (name != null) {
                found.put(player, name);
            }
        }
        return CompletableFuture.completedFuture(Collections.unmodifiableMap(found));
    }

    @Override
    public CompletableFuture<Boolean> isExempt(UUID player) {
        return CompletableFuture.completedFuture(exempt.contains(player));
    }

    @Override
    public CompletableFuture<Set<String>> directGroups(UUID player) {
        return CompletableFuture.completedFuture(groups.getOrDefault(player, Set.of("default")));
    }

    private static String key(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
