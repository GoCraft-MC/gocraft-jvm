package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Envelope;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/// Frames envelopes on a byte stream: a varint length, then the encoded
/// envelope.
///
/// A stream carries no message boundaries of its own, so the length is what
/// tells the reader where one envelope ends and the next begins. The host uses
/// exactly this framing; getting it wrong desynchronises the connection
/// permanently rather than failing one message.
///
/// Reads are single-consumer by contract — one reader thread owns the stream.
/// Writes are not serialised here at all: [Connection] owns a single writer
/// thread, which is what keeps two senders from interleaving their bytes.
final class Codec {

    /// What a peer may declare it is about to send. Without a bound, a runtime
    /// announcing a four-gigabyte frame would have this allocate four gigabytes
    /// before discovering the lie. Envelopes carry events and verdicts, never a
    /// bundle, so the limit is generous by a wide margin.
    static final int MAX_FRAME = 4 << 20;

    private final SocketChannel channel;
    private final ByteBuffer header = ByteBuffer.allocate(1);

    Codec(SocketChannel channel) {
        this.channel = channel;
    }

    /// Writes one envelope. The header and the payload go out in a single
    /// buffer: a header that reached the host without its payload would
    /// desynchronise the stream, and there is no way to take it back.
    void write(Envelope envelope) throws IOException {
        byte[] payload = envelope.toByteArray();
        if (payload.length > MAX_FRAME) {
            throw new IOException("envelope of " + payload.length + " bytes exceeds the " + MAX_FRAME + " byte limit");
        }
        ByteBuffer frame = ByteBuffer.allocate(varintSize(payload.length) + payload.length);
        putVarint(frame, payload.length);
        frame.put(payload);
        frame.flip();
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    }

    /// Reads the next envelope, blocking until one arrives.
    ///
    /// A clean end between frames is how an orderly shutdown ends and surfaces
    /// as [EOFException]; anything cut short mid-frame is a broken connection
    /// and surfaces as [IOException], so the two stay distinguishable.
    Envelope read() throws IOException {
        int length = readVarint();
        if (length > MAX_FRAME) {
            throw new IOException("host declared a " + length + " byte frame, over the " + MAX_FRAME + " byte limit");
        }
        byte[] payload = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("connection closed mid-frame");
            }
        }
        return Envelope.parseFrom(payload);
    }

    private int readVarint() throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = readByte();
            result |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                if (result < 0) {
                    throw new IOException("frame length is negative");
                }
                return result;
            }
        }
        throw new IOException("frame length varint is longer than five bytes");
    }

    private int readByte() throws IOException {
        header.clear();
        int read = channel.read(header);
        if (read < 0) {
            // Between frames, so this is the host closing cleanly.
            throw new EOFException("host closed the connection");
        }
        if (read == 0) {
            throw new IOException("blocking read returned nothing");
        }
        return header.flip().get() & 0xff;
    }

    static void putVarint(ByteBuffer buffer, int value) {
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            buffer.put((byte) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        buffer.put((byte) remaining);
    }

    static int varintSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }
}