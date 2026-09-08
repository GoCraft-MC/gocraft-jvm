package fr.gocraft.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks a class an event may carry as one of its fields.
///
///     @EventValue
///     public final class Tier {
///         private final String label;
///         private double price;              // subscribers may change it
///
///         public Tier(String label, double price) { … }
///
///         public String label() { return label; }
///         public double price() { return price; }
///         public void setPrice(double price) { this.price = price; }
///     }
///
///     @PluginEvent(value = "fr.oreo.shop/purchase", cancellable = true)
///     public final class PurchaseEvent {
///         private final PlayerRef buyer;
///         private final List<Tier> tiers;    // the list is fixed, its records are not
///         private double price;
///     }
///
/// Opt-in, and deliberately. A plugin event used to carry primitives, a string
/// and a byte array, which was safe for a reason worth keeping: the manifest
/// listed a flat set of names and types, so that list *was* the whole shape and
/// a build could compare two of them. A field that is a record breaks that —
/// the publisher knows its component order and a subscriber knows its own — so
/// records are declared in the manifest too, and only a class that says it is
/// one may be carried.
///
/// The rules are the event's rules, because a record is an event's payload one
/// level down: declaration order is the wire order, `final` means a subscriber
/// may not replace it, every field needs an accessor, a mutable one needs a
/// setter, and there must be a constructor taking every field in order.
///
/// A record cannot contain itself, directly or through another. The wire is a
/// finite positional payload with no pointers, so that is not a shape that
/// could be encoded at all.
///
/// It buys nothing on a class that only holds scalars — write those as fields
/// of the event. It exists for the shape a flat layout cannot express: a list
/// of things, each with several parts.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface EventValue {

    /// The name this record is known by in the manifest, and the one a
    /// subscriber's own copy is compared against.
    ///
    /// Empty means the class's fully qualified name, which is what an author
    /// wants unless two plugins want to spell the same record differently.
    String value() default "";
}