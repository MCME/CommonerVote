package com.mcmiddleearth.commonervote.rank;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.mcmiddleearth.commonervote.Permissions;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;

/**
 * {@link RankService} on top of the LuckPerms API. LuckPerms keeps one player table for the whole network, so a name
 * typed for a player who has only been on another server still resolves, and an offline player's groups and
 * permissions load from LuckPerms' storage.
 * <p>
 * The futures complete on LuckPerms' own threads. Nothing here touches the Bukkit scheduler: callers that need the
 * main thread hop to it themselves.
 */
public final class LuckPermsRankService implements RankService {

    private final LuckPerms lp;

    public LuckPermsRankService(LuckPerms lp) {
        this.lp = Objects.requireNonNull(lp);
    }

    @Override
    public CompletableFuture<Optional<UUID>> lookup(String name) {
        return lp.getUserManager().lookupUniqueId(name).thenApply(Optional::ofNullable);
    }

    /**
     * One LuckPerms lookup per player. A lookup that finds no name, or that fails, only leaves its own player out of
     * the map; the other players still get their names.
     */
    @Override
    public CompletableFuture<Map<UUID, String>> names(Collection<UUID> players) {
        Set<UUID> distinct = new LinkedHashSet<>(players);
        if (distinct.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        UserManager users = lp.getUserManager();
        Map<UUID, CompletableFuture<String>> lookups = new LinkedHashMap<>();
        for (UUID player : distinct) {
            lookups.put(player, users.lookupUsername(player).exceptionally(failure -> null));
        }
        return CompletableFuture.allOf(lookups.values().toArray(new CompletableFuture<?>[0])).thenApply(done -> {
            Map<UUID, String> found = new LinkedHashMap<>();
            lookups.forEach((player, lookup) -> {
                String name = lookup.join();
                if (name != null) {
                    found.put(player, name);
                }
            });
            return Collections.unmodifiableMap(found);
        });
    }

    /**
     * Loads the player (online or not), then asks LuckPerms for the exempt permission with the server's static
     * contexts, since an offline player has no world or gamemode contexts. Only an explicit true counts: an unset or
     * denied node is not exempt.
     */
    @Override
    public CompletableFuture<Boolean> isExempt(UUID player) {
        return lp.getUserManager().loadUser(player).thenApply(user -> user.getCachedData()
                .getPermissionData(lp.getContextManager().getStaticQueryOptions())
                .checkPermission(Permissions.EXEMPT)
                .asBoolean());
    }

    @Override
    public CompletableFuture<Set<String>> directGroups(UUID player) {
        return lp.getUserManager().loadUser(player).thenApply(user -> user.getNodes(NodeType.INHERITANCE).stream()
                .map(node -> node.getGroupName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet()));
    }
}
