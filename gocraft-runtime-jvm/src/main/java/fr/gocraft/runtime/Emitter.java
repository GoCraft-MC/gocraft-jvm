package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Emit;
import fr.gocraft.abi.v1.Emitted;
import fr.gocraft.abi.v1.Envelope;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// The runtime's half of the split sequence space.
///
/// Everything else on this socket answers something the host asked for. EMIT is
/// the one exchange a runtime starts, so both ends number their own requests —
/// and two counters over one space would eventually put the same number on a
/// request in flight in each direction, leaving each side to answer the other's
/// question. The host numbers odd, this numbers even, and zero belongs to
/// neither.
final class Emitter {

    private final Connection connection;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, CompletableFuture<Emitted>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    Emitter(Connection connection) {
        this.connection = connection;
    }

    /// Publishes one event and blocks until the host answers.
    ///
    /// No timeout of its own: the host bounds the dispatch with the event
    /// budget every subscriber shares, and always answers. If it dies instead,
    /// [#shutdown] completes the wait rather than leaving a virtual thread
    /// parked for the life of the process.
    Emitted emit(Emit emission) throws IOException {
        if (closed) {
            throw new IOException("the host connection is closed");
        }
        // Even, from 2.
        long seq = sequence.addAndGet(2);
        CompletableFuture<Emitted> answer = new CompletableFuture<>();
        pending.put(seq, answer);
        try {
            connection.send(Envelopes.emit(seq, emission));
            return answer.join();
        } catch (java.util.concurrent.CompletionException interrupted) {
            throw new IOException("the host went away while emitting", interrupted.getCause());
        } finally {
            pending.remove(seq);
        }
    }

    /// Wakes whoever published the emission this answers.
    ///
    /// It runs on the reader loop and must not block, which completing a future
    /// does not. The entry is removed on delivery, so a host answering the same
    /// emission twice cannot have its second answer taken for another's.
    void deliver(long seq, Emitted emitted) {
        CompletableFuture<Emitted> answer = pending.remove(seq);
        if (answer != null) {
            answer.complete(emitted);
        }
    }

    /// Fails every wait still outstanding. Called when the reader loop ends: a
    /// plugin parked on an answer that will never come would otherwise keep
    /// this process alive after the host is gone.
    void shutdown() {
        closed = true;
        pending.forEach((seq, answer) -> answer.completeExceptionally(
                new IOException("the host closed while an event was being published")));
        pending.clear();
    }
}