package com.mcmiddleearth.commonervote.network;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.mcmiddleearth.commonervote.domain.Settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * {@link Messenger} over the proxy's {@code BungeeCord} plugin-message channel, which Velocity's BungeeCord responder
 * also serves. A plugin message has to travel through a player's connection, so every send picks any online player
 * as the carrier, and does nothing (or falls back to this server) when nobody is online.
 * <p>
 * It is also the listener for the incoming channel: a recipient's server is told through the recipient's own
 * connection, so the player handed to {@code onUpdate} is the player who was notified.
 * <p>
 * With {@code useBungee} false (a single server) the channel is never registered and every send is skipped. All
 * methods run on the main thread.
 */
public final class BungeeMessenger implements Messenger, PluginMessageListener {

    public static final String CHANNEL = "BungeeCord";
    public static final String SUBCHANNEL = "CommonerVote";

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final boolean useBungee;
    private final BiConsumer<Player, Update> onUpdate;

    /**
     * @param settings the current shared settings, read each time an announcement is made
     * @param onUpdate called with the notified player and what happened, for each valid message received
     */
    public BungeeMessenger(Plugin plugin, Supplier<Settings> settings, boolean useBungee,
                           BiConsumer<Player, Update> onUpdate) {
        this.plugin = plugin;
        this.settings = settings;
        this.useBungee = useBungee;
        this.onUpdate = onUpdate;
    }

    /** Opens the {@code BungeeCord} channel in both directions, when a proxy is in use. */
    public void register() {
        if (!useBungee) {
            return;
        }
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    /** Closes what {@link #register} opened. */
    public void unregister() {
        if (!useBungee) {
            return;
        }
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
    }

    @Override
    public void notifyRecipient(String recipientName, Update kind) {
        if (!useBungee) {
            return;
        }
        carrier().ifPresent(carrier -> carrier.sendPluginMessage(plugin, CHANNEL,
                BungeeCodec.forwardToPlayer(recipientName, SUBCHANNEL, kind.payload())));
    }

    @Override
    public void announce(Component message) {
        Optional<Player> carrier = useBungee && settings.get().broadcastNetwork() ? carrier() : Optional.empty();
        if (carrier.isPresent()) {
            // The proxy sends it to every player on every server, this one included.
            carrier.get().sendPluginMessage(plugin, CHANNEL,
                    BungeeCodec.messageRaw("ALL", GsonComponentSerializer.gson().serialize(message)));
        } else {
            plugin.getServer().broadcast(message);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        Optional<String> payload;
        try {
            payload = BungeeCodec.readForwarded(message, SUBCHANNEL);
        } catch (IOException malformed) {
            return; // Not worth a log line: anyone on the network can send these.
        }
        payload.flatMap(Update::fromPayload).ifPresent(kind -> onUpdate.accept(player, kind));
    }

    private Optional<Player> carrier() {
        Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();
        return online.stream().findFirst().map(Player.class::cast);
    }
}
