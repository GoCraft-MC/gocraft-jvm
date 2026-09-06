package fr.gocraft.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Carries a type you cannot annotate, by saying what it looks like on the wire.
///
///     @ValueAdapter(ZonedDateTime.class)
///     public final class TimestampAdapter {
///         public static long encode(ZonedDateTime value) {
///             return value.toInstant().toEpochMilli();
///         }
///
///         public static ZonedDateTime decode(long wire) {
///             return Instant.ofEpochMilli(wire).atZone(ZoneOffset.UTC);
///         }
///     }
///
/// After that a field may be a `ZonedDateTime`, in an event, in a record, or
/// inside a `List` or a `Map` of one. The manifest says `int`, because that is
/// what crosses.
///
/// **The escape hatch is explicit on purpose.** [EventValue] covers a class the
/// author owns; this covers one they do not — a third-party type, a JDK type,
/// anything whose source they cannot put an annotation on. Without it the only
/// options are to give up on the field or to serialise an object graph nobody
/// on the far side can read, and §10 refuses both.
///
/// It also forces a decision rather than deferring one. The signature of
/// `encode` is the answer to "what does this look like to a Lua plugin", and
/// there is no way to write the adapter without answering it. A subscriber in
/// another language sees an ordinary `int` and needs to know nothing about
/// `ZonedDateTime`; the vocabulary the manifest can express stays closed, which
/// is what keeps N + N generators from becoming N × N bridges.
///
/// The rules the build checks:
///
///   - `public static W encode(T)` and `public static T decode(W)`, both on this
///     class. Static, because an adapter has no state to carry: it describes a
///     shape, it does not hold one.
///   - `W` is a type the wire already carries — a scalar, or a class marked
///     [EventValue]. An adapter that returned another adapted type would be a
///     chain nobody can read backwards.
///   - One adapter per target type. Two is refused naming both, because which
///     of them an event meant would otherwise depend on compilation order.
///
/// A subscriber's own copy of the event does not need the adapter, and usually
/// cannot have it: the class `gocraft-cli gen` writes declares the wire type,
/// since that is the whole of what the manifest describes.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface ValueAdapter {

    /// The type this adapter carries.
    Class<?> value();
}
