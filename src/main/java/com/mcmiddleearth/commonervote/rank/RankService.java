package com.mcmiddleearth.commonervote.rank;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What the plugin asks the rank plugin (LuckPerms) about players, whether they are online on this server or not. It
 * is an interface so the services can be tested with a fake.
 * <p>
 * Every answer is a future. The real implementation completes them on LuckPerms' own threads, never on the main
 * thread, so a caller that touches the Bukkit API hops back to the main thread first.
 */
public interface RankService {

    /**
     * Finds the player with this name anywhere on the network, whichever case it is typed in. The result is empty when
     * the rank plugin has never seen such a player.
     */
    CompletableFuture<Optional<UUID>> lookup(String name);

    /**
     * The last known name of each player. A player the rank plugin has never seen is left out of the map, so the map
     * can be smaller than the request.
     */
    CompletableFuture<Map<UUID, String>> names(Collection<UUID> players);

    /**
     * Whether this player holds the exempt permission, also when the player is offline. An exempt player is already a
     * Commoner (or staff) and takes no part in the voting.
     */
    CompletableFuture<Boolean> isExempt(UUID player);

    /**
     * The groups this player is directly a member of, lowercased. Groups that the player only inherits through another
     * group are not part of it. The promotion guard uses this to see that a player holds no group above the default.
     */
    CompletableFuture<Set<String>> directGroups(UUID player);
}
