package com.mcmiddleearth.commonervote.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MockBukkit validates outgoing plugin messages but does not record them, so these tests carry the byte-level proof
 * that the messages match what the proxy expects and what plugin 1.2.9 wrote and read.
 */
class BungeeCodecTest {

    @Test
    void forward_layout() throws IOException {
        byte[] bytes = BungeeCodec.forwardToPlayer("Bilbo_B", "CommonerVote", "Update");

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals("ForwardToPlayer", in.readUTF());
        assertEquals("Bilbo_B", in.readUTF());
        assertEquals("CommonerVote", in.readUTF());
        short length = in.readShort();
        byte[] inner = new byte[length];
        in.readFully(inner);
        assertEquals(0, in.available(), "nothing may follow the inner bytes");

        DataInputStream innerIn = new DataInputStream(new ByteArrayInputStream(inner));
        assertEquals("Update", innerIn.readUTF());
        assertEquals(0, innerIn.available(), "the inner bytes are one string");
    }

    @Test
    void forward_is_byte_identical_to_what_1_2_9_wrote() throws IOException {
        // 1.2.9's sender, as it was written: Guava's data output and an inner DataOutputStream.
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ForwardToPlayer");
        out.writeUTF("Bilbo_B");
        out.writeUTF("CommonerVote");
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        DataOutputStream msgout = new DataOutputStream(inner);
        msgout.writeUTF("Update");
        out.writeShort(inner.toByteArray().length);
        out.write(inner.toByteArray());

        assertArrayEquals(out.toByteArray(), BungeeCodec.forwardToPlayer("Bilbo_B", "CommonerVote", "Update"));
    }

    @Test
    void read_forwarded_round_trip() throws IOException {
        assertEquals(Optional.of("Voted"),
                BungeeCodec.readForwarded(received("CommonerVote", "Voted"), "CommonerVote"));
        assertEquals(Optional.of("Update"),
                BungeeCodec.readForwarded(received("CommonerVote", "Update"), "CommonerVote"));
        assertEquals(Optional.empty(),
                BungeeCodec.readForwarded(received("OtherPlugin", "Voted"), "CommonerVote"));
    }

    @Test
    void read_forwarded_reads_what_the_proxy_hands_on() throws IOException {
        // The proxy takes "ForwardToPlayer" and the player name off the front; the receiving server sees the rest.
        byte[] sent = BungeeCodec.forwardToPlayer("Bilbo_B", "CommonerVote", "Update");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(sent));
        in.readUTF();
        in.readUTF();
        byte[] atReceiver = in.readAllBytes();

        assertEquals(Optional.of("Update"), BungeeCodec.readForwarded(atReceiver, "CommonerVote"));

        // 1.2.9's receiver, as it was written.
        ByteArrayDataInput legacy = ByteStreams.newDataInput(atReceiver);
        assertEquals("CommonerVote", legacy.readUTF());
        short len = legacy.readShort();
        byte[] b = new byte[len];
        legacy.readFully(b);
        assertEquals("Update", new DataInputStream(new ByteArrayInputStream(b)).readUTF());
    }

    @Test
    void read_forwarded_reads_what_1_2_9_sends() throws IOException {
        // What a 1.2.9 server forwarded (it only ever sends "Update"), as the receiving server sees it.
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("CommonerVote");
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        new DataOutputStream(inner).writeUTF("Update");
        out.writeShort(inner.toByteArray().length);
        out.write(inner.toByteArray());

        assertEquals(Optional.of("Update"), BungeeCodec.readForwarded(out.toByteArray(), "CommonerVote"));
    }

    @Test
    void read_forwarded_ignores_other_subchannels_without_reading_further() throws IOException {
        // Another plugin's message: after the subchannel come bytes that are no concern of ours.
        byte[] foreign = {0, 5, 'O', 't', 'h', 'e', 'r', 0x7f};
        assertEquals(Optional.empty(), BungeeCodec.readForwarded(foreign, "CommonerVote"));
    }

    @Test
    void read_forwarded_rejects_malformed_bytes() {
        byte[] noLength = utf("CommonerVote");
        byte[] shortInner = concat(utf("CommonerVote"), new byte[] {0, 50, 0, 6});
        byte[] hugeLength = concat(utf("CommonerVote"), new byte[] {(byte) 0xff, (byte) 0xff, 0, 6});
        byte[] innerCutOff = concat(utf("CommonerVote"), new byte[] {0, 2, 0, 5});
        byte[] innerNotUtf = concat(utf("CommonerVote"), new byte[] {0, 3, 0, 1, (byte) 0xff});

        assertThrows(EOFException.class, () -> BungeeCodec.readForwarded(new byte[0], "CommonerVote"));
        assertThrows(EOFException.class, () -> BungeeCodec.readForwarded(new byte[] {0}, "CommonerVote"));
        assertThrows(EOFException.class, () -> BungeeCodec.readForwarded(noLength, "CommonerVote"));
        assertThrows(EOFException.class, () -> BungeeCodec.readForwarded(shortInner, "CommonerVote"));
        assertThrows(EOFException.class, () -> BungeeCodec.readForwarded(hugeLength, "CommonerVote"));
        assertThrows(EOFException.class, () -> BungeeCodec.readForwarded(innerCutOff, "CommonerVote"));
        assertThrows(IOException.class, () -> BungeeCodec.readForwarded(innerNotUtf, "CommonerVote"));
    }

    @Test
    void message_raw_layout() throws IOException {
        byte[] bytes = BungeeCodec.messageRaw("ALL", "{\"text\":\"x\"}");

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals("MessageRaw", in.readUTF());
        assertEquals("ALL", in.readUTF());
        assertEquals("{\"text\":\"x\"}", in.readUTF());
        assertEquals(0, in.available(), "nothing may follow the json");
    }

    @Test
    void message_raw_carries_text_beyond_ascii() throws IOException {
        String json = "{\"text\":\"" + (char) 0x2605 + " Bilbo_B " + (char) 0xe9 + "\"}";

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(BungeeCodec.messageRaw("ALL", json)));
        in.readUTF();
        in.readUTF();
        assertEquals(json, in.readUTF());
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

    private static byte[] utf(String text) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new DataOutputStream(out).writeUTF(text);
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] all = new byte[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
