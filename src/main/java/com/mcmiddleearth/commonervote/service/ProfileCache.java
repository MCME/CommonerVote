package com.mcmiddleearth.commonervote.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Server;

import com.destroystokyo.paper.profile.PlayerProfile;

/**
 * The name the server keeps in memory for a player who is not online: Paper's profile cache, which Paper fills from
 * {@code usercache.json} at startup and with everyone who joins. An interface, so the services can be tested without
 * Paper's profiles (MockBukkit has none).
 * <p>
 * It stands in for {@code Bukkit.getOfflinePlayer(id).getName()}, which for an offline player reads and parses that
 * player's data file from disk, every time: far too slow for the main thread, where names are shown.
 */
@FunctionalInterface
public interface ProfileCache {

    /** The cached name of this player, or empty when the cache has none. Never reads the disk or the network. */
    Optional<String> name(UUID player);

    /** Paper's own profile cache, read through {@link PlayerProfile#completeFromCache()}. */
    static ProfileCache of(Server server) {
        Objects.requireNonNull(server);
        return player -> {
            PlayerProfile profile = server.createProfile(player);
            profile.completeFromCache();
            return Optional.ofNullable(profile.getName()).filter(name -> !name.isBlank());
        };
    }
}
