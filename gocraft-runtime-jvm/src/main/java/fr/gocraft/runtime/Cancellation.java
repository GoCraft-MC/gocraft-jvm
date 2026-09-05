package fr.gocraft.runtime;

import fr.gocraft.api.EventControl;

/// One event's cancellation, shared by every handler that runs for it.
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
final class Cancellation implements EventControl {

    private boolean cancelled;

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean cancelled() {
        return cancelled;
    }
}