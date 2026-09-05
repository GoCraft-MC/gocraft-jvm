package fr.gocraft.api;

import java.util.List;

/// What `gocraft-apt` writes for a [PluginEvent] class.
///
/// It is the same three questions [CustomEvent] asks an author to answer, moved
/// off the event and onto a generated class beside it. The runtime resolves one
/// by name — `PurchaseEvent` gives `PurchaseEventLayout`, in the same package —
/// so nothing has to be registered and no index can fall out of step with the
/// classes it lists.
///
/// Public because the generated codec lives in the plugin's own jar and has to
/// implement something on the shared classpath. A plugin should never name it:
/// it is written by the build and read by the runtime, and an author reaching
/// for it is an author who wanted [CustomEvent].
public interface EventLayout {

    /// The namespaced name, from the annotation.
    String eventType();

    /// The event's values, in declaration order.
    List<Value> fields(Object event);

    /// Writes them back after every subscriber has run, in the same order.
    /// Only the mutable ones are assigned; a `final` field is skipped, because
    /// the host refused any write to it before this was ever called.
    void setFields(Object event, List<Value> fields);
}