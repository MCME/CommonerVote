package com.mcmiddleearth.commonervote.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * The bytes of the plugin messages on the {@code BungeeCord} channel, which the proxy (BungeeCord or Velocity's
 * BungeeCord responder) reads and plugin 1.2.9 still sends and reads during the rollout. Every layout here is
 * {@link DataOutputStream}'s, the same as Guava's {@code ByteStreams.newDataOutput()} that 1.2.9 used.
 */
public final class BungeeCodec {

    private BungeeCodec() {
    }

    /**
     * A message for the proxy to pass on to the server where this player is. The receiving server sees what comes
     * after the player's name: the subchannel, the length of the inner bytes as a short, and the inner bytes, which
     * are the payload as one UTF string.
     *
     * @throws UncheckedIOException when a text is too long to encode (65535 bytes of UTF)
     */
    public static byte[] forwardToPlayer(String player, String subchannel, String payload) {
        return write(out -> {
            out.writeUTF("ForwardToPlayer");
            out.writeUTF(player);
            out.writeUTF(subchannel);
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            new DataOutputStream(inner).writeUTF(payload);
            out.writeShort(inner.size());
            out.write(inner.toByteArray());
        });
    }

    /**
     * A message for the proxy to show as chat to {@code target}, which is a player's name, a server's name or
     * {@code ALL}. The text is a component in JSON.
     *
     * @throws UncheckedIOException when a text is too long to encode (65535 bytes of UTF)
     */
    public static byte[] messageRaw(String target, String json) {
        return write(out -> {
            out.writeUTF("MessageRaw");
            out.writeUTF(target);
            out.writeUTF(json);
        });
    }

    /**
     * Reads a message that a server received from the proxy: the subchannel, the length of the inner bytes as a
     * short, and the inner bytes, which are one UTF string.
     *
     * @return the payload, or empty when the message belongs to another subchannel (the rest is then not read)
     * @throws IOException when the message is cut short or the inner bytes are not a UTF string
     */
    public static Optional<String> readForwarded(byte[] message, String subchannel) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
        if (!in.readUTF().equals(subchannel)) {
            return Optional.empty();
        }
        // Unsigned: a length above 32767 then runs past the end of the message (an EOFException), not into a
        // negative array size.
        byte[] inner = new byte[in.readUnsignedShort()];
        in.readFully(inner);
        return Optional.of(new DataInputStream(new ByteArrayInputStream(inner)).readUTF());
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            writer.write(new DataOutputStream(bytes));
        } catch (IOException e) {
            // Writing to memory cannot fail, except for a text of more than 65535 UTF bytes.
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
