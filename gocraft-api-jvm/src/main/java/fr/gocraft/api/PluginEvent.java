package fr.gocraft.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks an ordinary class as an event this plugin publishes.
///
///     @PluginEvent(value = "fr.oreo.shop/purchase", cancellable = true)
///     public final class PurchaseEvent {
///         private final PlayerRef player;   // final   → read-only
///         private double price;             // mutable → subscribers may change it
///
///         public PlayerRef player() { return player; }
///         public double price() { return price; }
///         public void setPrice(double price) { this.price = price; }
///     }
///
/// `gocraft-apt` reads the class and writes the codec; the author never sees
/// it and never writes an index by hand. Which field is at which position comes
/// from declaration order, and whether a subscriber may replace one comes from
/// `final` — so the two things easiest to get out of step with the manifest are
/// derived from the code rather than repeated beside it.
///
/// The alternative is [CustomEvent], where the layout is written by hand. Both
/// reach the same wire; pick per event, mix within a plugin.
///
/// Retained in the class file and not at runtime: what the runtime needs is the
/// generated codec, which carries the type name itself. An annotation the
/// runtime had to read would be a second copy of it.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface PluginEvent {

    /// The namespaced name, exactly as `[[events.provides]]` spells it. The
    /// slash is required: it is what makes shadowing a native event impossible.
    String value();

    /// Whether a subscriber may cancel it. A plugin publishing an event nobody
    /// may refuse says so here, and the host enforces it.
    boolean cancellable() default false;

    /// Whether a subscriber failing cancels the event rather than being
    /// skipped — the `on_failure = DENY` of §06.
    ///
    /// It belongs to the event and not to its subscribers: whether losing one
    /// is survivable is a fact about what the event guards, and the plugin
    /// publishing it is the only party that knows.
    boolean failClosed() default false;
}