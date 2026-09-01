package fr.gocraft.api;

/// Where a handler runs among the others subscribed to the same event.
///
/// The order is the host's and spans every runtime at once: a Lua handler at
/// HIGH runs before a Java one at NORMAL. Within a priority, plugin id decides,
/// so the order is deterministic rather than merely stable.
///
/// Declaration order here is the run order.
public enum Priority {

    /// Runs first. For handlers that set something up the others will read.
    LOWEST,

    LOW,

    NORMAL,

    HIGH,

    /// Runs last among the handlers that can still change the outcome. This is
    /// where a protection plugin belongs: it gets the final word.
    HIGHEST,

    /// Runs after everything, and must not change anything. For logging and
    /// metrics that need to see what was actually decided. Cancelling from here
    /// is a bug — the decision has already been made and reported.
    MONITOR
}
