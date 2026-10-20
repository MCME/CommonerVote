package com.mcmiddleearth.commonervote.network;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.domain.Settings;
import com.mcmiddleearth.commonervote.network.Messenger.Update;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The messenger itself is thin: the byte layouts are pinned in {@link BungeeCodecTest}. These tests pin the decisions
 * around it, with Mockito doubles for the server, since MockBukkit does not record outgoing plugin messages.
 */
class BungeeMessengerTest {

    private record Received(Player player, Update kind) {
    }

    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final org.bukkit.plugin.messaging.Messenger bukkitMessenger =
            mock(org.bukkit.plugin.messaging.Messenger.class);
    private final Player carrier = mock(Player.class);
    private final Player recipient = mock(Player.class);
    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.defaults());
    private final List<Received> updates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getMessenger()).thenReturn(bukkitMessenger);
    }

    private BungeeMessenger messenger(boolean useBungee) {
        return new BungeeMessenger(plugin, settings::get, useBungee, (p, kind) -> updates.add(new Received(p, kind)));
    }

    private void online(Player... players) {
        doReturn(List.of(players)).when(server).getOnlinePlayers();
    }

    private static Settings withoutNetworkBroadcast() {
        return Settings.fromMap(Map.of("broadcastNetwork", "false"), warning -> { });
    }

    // register / unregister

    @Test
    void register_opens_the_bungeecord_channel_in_both_directions() {
        BungeeMessenger messenger = messenger(true);

        messenger.register();

        verify(bukkitMessenger).registerOutgoingPluginChannel(plugin, "BungeeCord");
        verify(bukkitMessenger).registerIncomingPluginChannel(plugin, "BungeeCord", messenger);
    }

    @Test
    void unregister_closes_both_directions() {
        BungeeMessenger messenger = messenger(true);

        messenger.unregister();

        verify(bukkitMessenger).unregisterOutgoingPluginChannel(plugin, "BungeeCord");
        verify(bukkitMessenger).unregisterIncomingPluginChannel(plugin, "BungeeCord", messenger);
    }

    @Test
    void register_and_unregister_do_nothing_without_a_proxy() {
        BungeeMessenger messenger = messenger(false);

        messenger.register();
        messenger.unregister();

        verifyNoInteractions(bukkitMessenger);
    }

    // notifyRecipient

    @Test
    void notify_forwards_through_any_online_player() throws IOException {
        online(carrier);

        messenger(true).notifyRecipient("Bilbo_B", Update.VOTED);

        verify(carrier).sendPluginMessage(plugin, "BungeeCord",
                BungeeCodec.forwardToPlayer("Bilbo_B", "CommonerVote", "Voted"));
    }

    @Test
    void notify_update_sends_the_update_payload() {
        online(carrier);

        messenger(true).notifyRecipient("Bilbo_B", Update.UPDATE);

        verify(carrier).sendPluginMessage(plugin, "BungeeCord",
                BungeeCodec.forwardToPlayer("Bilbo_B", "CommonerVote", "Update"));
    }

    @Test
    void notify_sends_nothing_when_nobody_is_online() {
        online();

        assertDoesNotThrow(() -> messenger(true).notifyRecipient("Bilbo_B", Update.VOTED));

        verify(carrier, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    void notify_sends_nothing_without_a_proxy() {
        online(carrier);

        messenger(false).notifyRecipient("Bilbo_B", Update.VOTED);

        verify(carrier, never()).sendPluginMessage(any(), any(), any());
    }

    // announce

    @Test
    void announce_goes_to_the_whole_network_through_any_online_player() {
        online(carrier, recipient);
        Component message = Component.text("Bilbo_B has been promoted", NamedTextColor.GOLD);

        messenger(true).announce(message);

        verify(carrier).sendPluginMessage(plugin, "BungeeCord",
                BungeeCodec.messageRaw("ALL", GsonComponentSerializer.gson().serialize(message)));
        verify(server, never()).broadcast(any(Component.class));
    }

    @Test
    void announce_is_local_when_the_network_announce_is_off() {
        online(carrier);
        settings.set(withoutNetworkBroadcast());
        Component message = Component.text("Bilbo_B has been promoted");

        messenger(true).announce(message);

        verify(server).broadcast(message);
        verify(carrier, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    void announce_reads_the_settings_when_it_is_called() {
        online(carrier);
        BungeeMessenger messenger = messenger(true);
        settings.set(withoutNetworkBroadcast());
        Component message = Component.text("Bilbo_B has been promoted");

        messenger.announce(message);

        verify(server).broadcast(message);
        verify(carrier, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    void announce_is_local_without_a_proxy() {
        online(carrier);
        Component message = Component.text("Bilbo_B has been promoted");

        messenger(false).announce(message);

        verify(server).broadcast(message);
        verify(carrier, never()).sendPluginMessage(any(), any(), any());
    }

    @Test
    void announce_is_local_when_nobody_is_online_to_carry_it() {
        online();
        Component message = Component.text("Bilbo_B has been promoted");

        messenger(true).announce(message);

        verify(server).broadcast(message);
    }

    // onPluginMessageReceived

    @Test
    void received_voted_and_update_reach_the_callback_with_the_player() throws IOException {
        BungeeMessenger messenger = messenger(true);

        messenger.onPluginMessageReceived("BungeeCord", recipient, received("CommonerVote", "Voted"));
        messenger.onPluginMessageReceived("BungeeCord", recipient, received("CommonerVote", "Update"));

        assertEquals(List.of(new Received(recipient, Update.VOTED), new Received(recipient, Update.UPDATE)), updates);
    }

    @Test
    void received_message_of_another_channel_is_ignored() throws IOException {
        messenger(true).onPluginMessageReceived("minecraft:brand", recipient, received("CommonerVote", "Voted"));

        assertTrue(updates.isEmpty());
    }

    @Test
    void received_message_of_another_plugin_is_ignored() throws IOException {
        messenger(true).onPluginMessageReceived("BungeeCord", recipient, received("OtherPlugin", "Voted"));

        assertTrue(updates.isEmpty());
    }

    @Test
    void received_unknown_payload_is_ignored() throws IOException {
        BungeeMessenger messenger = messenger(true);

        messenger.onPluginMessageReceived("BungeeCord", recipient, received("CommonerVote", "Promoted"));
        messenger.onPluginMessageReceived("BungeeCord", recipient, received("CommonerVote", "voted"));

        assertTrue(updates.isEmpty());
    }

    @Test
    void received_malformed_bytes_do_not_throw_out_of_the_listener() {
        BungeeMessenger messenger = messenger(true);
        byte[][] garbage = {
                new byte[0],
                {0},
                {(byte) 0xff, (byte) 0xff, (byte) 0xff},
                {0, 12, 'C', 'o', 'm', 'm', 'o', 'n', 'e', 'r', 'V', 'o', 't', 'e'},
                {0, 12, 'C', 'o', 'm', 'm', 'o', 'n', 'e', 'r', 'V', 'o', 't', 'e', (byte) 0xff, (byte) 0xff, 0, 6},
        };

        for (byte[] bytes : garbage) {
            assertDoesNotThrow(() -> messenger.onPluginMessageReceived("BungeeCord", recipient, bytes));
        }

        assertTrue(updates.isEmpty());
    }

    /** A message as the receiving server gets it: the subchannel, the length, then the inner bytes. */
    private static byte[] received(String subchannel, String payload) throws IOException {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        new DataOutputStream(inner).writeUTF(payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeUTF(subchannel);
        data.writeShort(inner.size());
        data.write(inner.toByteArray());
        return out.toByteArray();
    }
}
