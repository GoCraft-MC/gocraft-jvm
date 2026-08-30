package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Hello;
import fr.gocraft.abi.v1.Load;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Framing tests over a real Unix socket pair.
///
/// A mock stream would not exercise the thing most likely to be wrong: a
/// channel read that returns fewer bytes than asked for. Over a real socket the
/// codec has to loop, and a version that assumed one read per frame passes
/// against a buffer and fails here.
class CodecTest {

    /// Opens a connected pair and hands both ends to the body.
    private void withSocketPair(SocketPairTest body) throws Exception {
        Path directory = Files.createTempDirectory("gc");
        // Well under the 107-byte AF_UNIX limit, which is not a suggestion:
        // 108 fails with "invalid argument" and names nothing useful.
        Path socket = directory.resolve("t.sock");
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socket);
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(address);
            try (SocketChannel client = SocketChannel.open(address);
                 SocketChannel server = listener.accept()) {
                body.accept(client, server);
            }
        } finally {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(directory);
        }
    }

    private interface SocketPairTest {
        void accept(SocketChannel client, SocketChannel server) throws Exception;
    }

    @Test
    void roundTripsAnEnvelope() throws Exception {
        withSocketPair((client, server) -> {
            Envelope sent = Envelope.newBuilder()
                    .setSeq(7)
                    .setHello(Hello.newBuilder().setAbi(1).setRuntime("jvm 25.0.3"))
                    .build();

            new Codec(client).write(sent);
            Envelope received = new Codec(server).read();

            assertEquals(7, received.getSeq());
            assertTrue(received.hasHello());
            assertEquals(1, received.getHello().getAbi());
            assertEquals("jvm 25.0.3", received.getHello().getRuntime());
        });
    }

    /// The stream carries no boundaries of its own, so a reader that got the
    /// length wrong would decode the next frame as part of this one. Three in a
    /// row is what catches it.
    @Test
    void keepsConsecutiveFramesApart() throws Exception {
        withSocketPair((client, server) -> {
            Codec writer = new Codec(client);
            for (int index = 0; index < 3; index++) {
                writer.write(Envelope.newBuilder()
                        .setSeq(index)
                        .setLoad(Load.newBuilder().setPluginId("plugin-" + index))
                        .build());
            }

            Codec reader = new Codec(server);
            for (int index = 0; index < 3; index++) {
                Envelope received = reader.read();
                assertEquals(index, received.getSeq());
                assertEquals("plugin-" + index, received.getLoad().getPluginId());
            }
        });
    }

    /// A payload past the single-byte varint boundary. The length prefix is
    /// where an off-by-one hides, and a short message never reaches the second
    /// byte to expose it.
    @Test
    void framesAPayloadLongerThanOneVarintByte() throws Exception {
        withSocketPair((client, server) -> {
            String name = "x".repeat(500);
            new Codec(client).write(Envelope.newBuilder()
                    .setLoad(Load.newBuilder().setPluginId(name))
                    .build());

            Envelope received = new Codec(server).read();
            assertEquals(name, received.getLoad().getPluginId());
        });
    }

    /// Closing between frames is how an orderly shutdown ends. It has to stay
    /// distinguishable from a connection cut mid-frame, or the runtime reports
    /// a crash every time the server stops normally.
    @Test
    void reportsACleanCloseAsEndOfStream() throws Exception {
        withSocketPair((client, server) -> {
            client.close();
            assertThrows(java.io.EOFException.class, () -> new Codec(server).read());
        });
    }

    @Test
    void refusesAFrameOverTheLimit() throws Exception {
        withSocketPair((client, server) -> {
            // A declared length only — nothing follows it. A codec that
            // allocated before checking would ask for the whole amount.
            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(8);
            Codec.putVarint(header, Codec.MAX_FRAME + 1);
            header.flip();
            while (header.hasRemaining()) {
                client.write(header);
            }

            IOException failure = assertThrows(IOException.class, () -> new Codec(server).read());
            assertTrue(failure.getMessage().contains("over the"), failure.getMessage());
        });
    }

    @Test
    void encodesVarintsAtTheirBoundaries() {
        assertEquals(1, Codec.varintSize(0));
        assertEquals(1, Codec.varintSize(127));
        assertEquals(2, Codec.varintSize(128));
        assertEquals(2, Codec.varintSize(16_383));
        assertEquals(3, Codec.varintSize(16_384));
    }
}