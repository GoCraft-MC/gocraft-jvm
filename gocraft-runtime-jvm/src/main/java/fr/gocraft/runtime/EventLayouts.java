package fr.gocraft.runtime;

import fr.gocraft.api.EventLayout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Finds the codec `gocraft-apt` wrote for one event class.
///
/// By name — `PurchaseEvent` gives `PurchaseEventLayout`, in the same package —
/// rather than through an index the build would have to keep in step with the
/// classes it lists. An index that fell behind would name a codec for an event
/// that changed shape, which is the one failure this whole layer exists to make
/// impossible.
///
/// Loaded through the event's own classloader, which is the plugin's: two
/// plugins may each define a class of the same name, and resolving through this
/// runtime's loader would hand one of them the other's codec.
///
/// Cached per class. The map is keyed by Class, so unloading a plugin has to
/// drop its entries or the classloader stays reachable — the leak §13 names by
/// another route. [#forget] is what unload calls.
final class EventLayouts {

    private static final Map<Class<?>, EventLayout> CACHE = new ConcurrentHashMap<>();

    private EventLayouts() {
    }

    /// The codec for this event, or null when the class was never annotated.
    static EventLayout of(Class<?> event) {
        return CACHE.computeIfAbsent(event, EventLayouts::resolve);
    }

    /// Drops every codec loaded by this classloader, so unloading a plugin does
    /// not leave its classes reachable from a static map.
    static void forget(ClassLoader loader) {
        CACHE.keySet().removeIf(event -> event.getClassLoader() == loader);
    }

    private static EventLayout resolve(Class<?> event) {
        String name = event.getName() + "Layout";
        try {
            Class<?> generated = Class.forName(name, true, event.getClassLoader());
            return (EventLayout) generated.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException absent) {
            // Not annotated, or compiled without the processor on the path. The
            // caller turns this into a message naming both ways to declare an
            // event, because from here the two are indistinguishable.
            return null;
        } catch (ReflectiveOperationException | ClassCastException broken) {
            throw new IllegalStateException(name + " is not a usable event codec; rebuild the "
                    + "plugin with gocraft-apt on the annotation processor path", broken);
        }
    }
}