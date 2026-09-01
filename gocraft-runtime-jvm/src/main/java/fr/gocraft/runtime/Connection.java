package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Envelope;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/// The connection to the host, with one thread dedicated to writing it.
///
/// The writer exists because framing has to be serialised: two threads encoding
/// into the same stream would interleave their bytes and corrupt every frame
/// that follows. A lock would do as well now that `synchronized` no longer pins
/// virtual threads (JEP 491), but a queue also decouples a slow socket from the
/// dispatch that produced the message — a handler answering an event must not
/// block on the host getting round to reading it.
///
/// Reading stays on the caller's thread. [Main] owns that loop and does nothing
/// in it but route, so nothing can hold up the next frame.
final class Connection implements AutoCloseable {

    /// Closes the queue. A sentinel rather than an interrupt, so the writer
    /// drains what was already queued before it stops — the last thing sent is
    /// usually the reply that lets the host shut down cleanly.
    private static final Envelope POISON = Envelope.getDefaultInstance();

    private final Codec codec;
    private final SocketChannel channel;
    private final BlockingQueue<Envelope> outbound = new LinkedBlockingQueue<>();
    private final Thread writer;
    private final AtomicReference<IOException> writeFailure = new AtomicReference<>();

    Connection(SocketChannel channel) {
        this.channel = channel;
        this.codec = new Codec(channel);
        this.writer = Thread.ofPlatform()
                .name("gocraft-writer")
                .start(this::drain);
    }

    /// Queues one envelope. It returns as soon as the message is accepted, not
    /// when the host has it.
    ///
    /// A failed write surfaces on the next call rather than being swallowed:
    /// the writer runs on its own thread, so there is nowhere else to raise it,
    /// and a runtime that cannot answer the host has to stop rather than keep
    /// queueing into a dead socket.
    void send(Envelope envelope) throws IOException {
        IOException failed = writeFailure.get();
        if (failed != null) {
            throw failed;
        }
        outbound.add(envelope);
    }

    Envelope receive() throws IOException {
        return codec.read();
    }

    private void drain() {
        try {
            while (true) {
                Envelope envelope = outbound.take();
                if (envelope == POISON) {
                    return;
                }
                codec.write(envelope);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            writeFailure.compareAndSet(null, failure);
        }
    }

    @Override
    public void close() throws IOException {
        outbound.add(POISON);
        try {
            // Bounded: a host that stopped reading must not keep this process
            // alive, and whatever is left in the queue is worth less than
            // exiting.
            writer.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        channel.close();
    }
}