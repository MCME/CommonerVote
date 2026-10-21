package com.mcmiddleearth.commonervote.network;

import java.util.Optional;

import net.kyori.adventure.text.Component;

/**
 * How this plugin reaches players who are on other servers of the network. The services talk to this interface; the
 * BungeeCord/Velocity plugin-message implementation is {@link BungeeMessenger}, and tests use a recording one.
 * <p>
 * Both methods are called on the main thread.
 */
public interface Messenger {

    /**
     * Tells the server where this player is playing that something changed, so it can refresh the player's XP bar or
     * show the welcome. Does nothing when no proxy is configured or when nobody is online here to carry the message.
     *
     * @param recipientName the player's name; the proxy finds the player by it, whichever case it is typed in
     */
    void notifyRecipient(String recipientName, Update kind);

    /**
     * Shows this message to everybody on the network (or only to this server's players when the network announce is
     * switched off or cannot be sent).
     */
    void announce(Component message);

    /**
     * What a recipient's server is told. During the rollout, servers on plugin 1.2.9 receive these too.
     */
    enum Update {
        /** A vote was cast for the player. New in 2.0: a 1.2.9 server does not know this payload and ignores it. */
        VOTED("Voted"),
        /** The player's votes changed: check the score again and refresh the XP bar. A 1.2.9 server does the same. */
        UPDATE("Update");

        private final String payload;

        Update(String payload) {
            this.payload = payload;
        }

        /** The text sent over the wire. */
        public String payload() {
            return payload;
        }

        /** The kind with this payload, or empty for a payload this version does not know. */
        public static Optional<Update> fromPayload(String payload) {
            for (Update update : values()) {
                if (update.payload.equals(payload)) {
                    return Optional.of(update);
                }
            }
            return Optional.empty();
        }
    }
}
