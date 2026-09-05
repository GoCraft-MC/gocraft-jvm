package fr.gocraft.api;

import java.util.List;

/// A plugin-defined event this plugin can publish.
///
/// Native events are compiled into the runtime and a plugin cannot add to that
/// schema, so an event of its own takes the dynamic path: the layout is
/// declared once in `plugin.toml` under `[[events.provides]]`, and both ends
/// know it before the first event fires. Nothing carries field names on the
/// wire.
///
///     public final class PurchaseEvent implements CustomEvent {
///         private final String player;
///         private double price;
///
///         public String eventType() { return "fr.oreo.shop/purchase"; }
///
///         public List<Value> fields() {
///             return List.of(new Value.Text(player), new Value.Decimal(price));
///         }
///
///         public void setFields(List<Value> fields) {
///             if (fields.get(1) instanceof Value.Decimal(double v)) price = v;
///         }
///     }
///
/// This is not [Event]. That one is what the host dispatches *to* a handler and
/// its subclasses are generated; this is what a plugin publishes, and the
/// author writes the class. A plugin may well do both, with two types.
///
/// [#fields] and [#setFields] are the same list in the same order, and that
/// order is the contract — the one the manifest declares. Appending a field is
/// safe; reordering one silently shifts every index for everyone who compiled
/// against the previous version, which is why the build compares the layout
/// against the one it wrote last time.
public interface CustomEvent {

    /// The namespaced name, exactly as `[[events.provides]]` spells it:
    /// `"fr.oreo.shop/purchase"`.
    String eventType();

    /// This event's values, in declaration order.
    List<Value> fields();

    /// Receives them back once every subscriber has run, in the same order,
    /// with whatever they changed already applied.
    ///
    /// This is what makes a dispatch across two processes and two languages
    /// feel in-process: the object published is the object read afterwards. A
    /// field nobody touched arrives unchanged, so an implementation may assign
    /// every field rather than work out which moved.
    void setFields(List<Value> fields);
}