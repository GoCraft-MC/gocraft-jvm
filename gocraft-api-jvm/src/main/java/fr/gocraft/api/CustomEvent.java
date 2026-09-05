package fr.gocraft.api;

import java.util.List;

/// The codec `gocraft-apt` writes for one [PluginEvent] class.
///
/// Native events are compiled into the runtime and a plugin cannot add to that
/// schema, so an event of its own takes the dynamic path: the layout is
/// declared once in `plugin.toml` under `[[events.provides]]`, and both ends
/// know it before the first event fires. Nothing carries field names on the
/// wire.
///
/// An author writes the event and nothing else:
///
///     @PluginEvent("fr.oreo.shop/purchase")
///     public final class PurchaseEvent {
///         private final String player;
///         private double price;
///
///         public PurchaseEvent(String player, double price) { … }
///
///         public String player() { return player; }
///         public double price() { return price; }
///         public void price(double price) { this.price = price; }
///     }
///
/// There is deliberately no second way to say it. An interface an event could
/// implement by hand would be the same layout written twice — once in the class
/// and once in the manifest the build checks it against — and the two would be
/// free to disagree in the one place nothing compares them. The annotation is
/// the single source, and this is what the build derives from it.
///
/// Which is why the methods take the event rather than being on it. One codec
/// serves every instance of its class and the runtime caches it, so publishing
/// an event allocates the event and nothing else. It also means the generated
/// class can live in the plugin's own jar while implementing something on the
/// shared classpath, which is what lets two plugins each define an event of the
/// same name without meeting.
///
/// This is not [Event]. That one is what the host dispatches *to* a handler and
/// its subclasses are generated from the ABI schema; this describes an event a
/// plugin defined. A plugin may well use both.
///
/// A plugin should never name this type. It is written by the build and read by
/// the runtime.
public interface CustomEvent {

    /// The namespaced name, from the annotation: `"fr.oreo.shop/purchase"`.
    String eventType();

    /// Whether a subscriber may refuse it, from the annotation.
    ///
    /// Carried here because @PluginEvent is retained in the class file and not
    /// at runtime: what the runtime needs is the codec, and an annotation it had
    /// to read back would be a second copy of what the build already knew. It
    /// decides whether a handler may ask for an [EventControl].
    boolean cancellable();

    /// The event's values, in declaration order.
    ///
    /// That order is the contract — the one the manifest declares. Appending a
    /// field is safe; reordering one silently shifts every index for everyone
    /// who compiled against the previous version, which is why the build
    /// compares this layout against the one it wrote last time.
    List<Value> fields(Object event);

    /// Writes them back into an event that was published, once every subscriber
    /// has run, with whatever they changed already applied.
    ///
    /// This is what makes a dispatch across two processes and two languages feel
    /// in-process: the object published is the object read afterwards. Only the
    /// mutable fields are assigned — a `final` one is skipped, because the host
    /// refused any write to it before this was called.
    void setFields(Object event, List<Value> fields);

    /// Builds an event from values that arrived, for a plugin subscribed to
    /// somebody else's event.
    ///
    /// The subscriber declares its own class matching the provider's layout;
    /// there is no shared type, because the two plugins are loaded by different
    /// classloaders and may not even be written in the same language. So the
    /// build requires a constructor taking every field in declaration order,
    /// and a class that has none is a compile error rather than an event that
    /// cannot be delivered.
    ///
    /// The sink is for the handles inside the payload. An event carrying a
    /// [PlayerRef] hands its subscriber somebody it can answer, exactly as a
    /// native event does — which is the whole reason PlayerRef is in the
    /// vocabulary rather than being sixteen bytes the handler has to turn into
    /// a handle itself.
    Object create(List<Value> fields, EffectSink sink);
}