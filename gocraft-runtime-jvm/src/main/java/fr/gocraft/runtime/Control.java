package fr.gocraft.runtime;

import fr.gocraft.api.EffectSink;
import fr.gocraft.api.EventControl;
import fr.gocraft.api.PlayerRef;
import fr.gocraft.api.Value;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// One event's channel back to the host, shared by every handler that runs for
/// it.
///
/// Not thread-safe and does not need to be: the handlers for one event run in
/// priority order on the virtual thread the dispatch was given, one after
/// another. A later handler reading [#cancelled()] sees what an earlier one
/// decided, which is the whole reason it is shared rather than one per handler.
///
/// Cancelling is a vote. The host has the event definition and arbitrates —
/// cancelling something it declared uncancellable is logged and ignored there —
/// so nothing here refuses a call, and the handler that could not have made one
/// was stopped at registration instead.
///
/// It is also the sink every handle in the payload was bound to, so effects
/// accumulate here whichever noun they were asked of, and travel back together
/// in the verdict.
final class Control implements EventControl, EffectSink {

    private final List<Effect> effects = new ArrayList<>();
    private boolean cancelled;
    private boolean sealed;

    /// One queued host call: what to do, and the values that say to whom.
    record Effect(String call, List<Value> values) {
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean cancelled() {
        return cancelled;
    }

    @Override
    public PlayerRef player(byte[] uuid) {
        if (uuid == null || uuid.length != 16) {
            throw new IllegalArgumentException("a player is named by a 16-byte uuid, got "
                    + (uuid == null ? "null" : uuid.length + " bytes"));
        }
        ByteBuffer buffer = ByteBuffer.wrap(uuid);
        return new PlayerRef(new UUID(buffer.getLong(), buffer.getLong()),
                "", PlayerRef.Edition.UNKNOWN, this);
    }

    @Override
    public void add(String call, List<Value> values) {
        if (sealed) {
            throw new IllegalStateException("this handle belongs to a dispatch that has "
                    + "already been answered; " + call + " will never be performed");
        }
        effects.add(new Effect(call, List.copyOf(values)));
    }

    /// What the handlers asked the host to do, in the order they asked, after
    /// which nothing more may be queued.
    ///
    /// Sealing here is what turns "a handler kept a handle and used it two ticks
    /// later" from an effect that silently goes nowhere into an exception naming
    /// what happened.
    List<Effect> seal() {
        sealed = true;
        return List.copyOf(effects);
    }
}