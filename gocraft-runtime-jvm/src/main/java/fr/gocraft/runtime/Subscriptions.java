package fr.gocraft.runtime;

import fr.gocraft.api.CustomEvent;
import fr.gocraft.api.EventControl;
import fr.gocraft.api.Priority;
import fr.gocraft.api.Subscribe;
import fr.gocraft.api.event.GeneratedEvents;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The handlers one plugin registered, by event type.
///
/// Handlers live wherever the author put them. §05's protection plugin keeps
/// them on a `ProtectionListener` it can unit-test with `new` and no server, so
/// this takes any object rather than only the plugin: the plugin instance is
/// registered for convenience, and anything else through
/// [fr.gocraft.api.Host#registerListener].
///
/// The parameter type of an annotated method is the subscription, which means
/// there is no event name to misspell and a handler for a class this runtime
/// does not know fails at registration rather than never being called.
///
/// **This holds Method objects, and a Method retains its declaring class.** It
/// is one of the references §13 warns about: left alive after unload it keeps
/// the plugin's classloader and every class in it. [#clear()] exists for that,
/// and LoadedPlugin calls it.
final class Subscriptions {

    private final Map<String, List<Handler>> byType = new LinkedHashMap<>();

    /// The codec for each plugin-defined event some handler here subscribed to.
    ///
    /// A native event has a generated factory the runtime finds by type; a
    /// plugin-defined one does not, and the class this plugin declared is the
    /// only description of the payload anybody on this side has.
    private final Map<String, CustomEvent> codecs = new LinkedHashMap<>();

    private record Handler(Object target, Method method, Priority priority, boolean wantsControl) {
        void invoke(Object event, EventControl control) throws ReflectiveOperationException {
            if (wantsControl) {
                method.invoke(target, event, control);
            } else {
                method.invoke(target, event);
            }
        }
    }

    /// What a handler's first parameter turned out to be.
    private record Subscribed(String type, boolean cancellable, CustomEvent codec) {
    }

    /// A handler the runtime refuses to register, with the reason.
    static final class InvalidHandler extends Exception {
        InvalidHandler(String reason) {
            super(reason);
        }
    }

    /// Reads every @Subscribe method off one object.
    ///
    /// A malformed handler is refused rather than skipped. An author who wrote
    /// one meant it to run, and a subscription quietly dropped is
    /// indistinguishable from an event that never fires — which is the worst
    /// afternoon in plugin development.
    ///
    /// Registering the same object twice is refused for the same reason it
    /// matters: it would run every one of its handlers twice per event, and the
    /// second run would see what the first decided.
    void register(Object listener) throws InvalidHandler {
        if (listener == null) {
            throw new InvalidHandler("a listener cannot be null");
        }
        if (alreadyRegistered(listener)) {
            throw new InvalidHandler(listener.getClass().getName()
                    + " is already registered; its handlers would run twice per event");
        }
        List<Method> annotated = new ArrayList<>();
        for (Method method : listener.getClass().getMethods()) {
            if (method.isAnnotationPresent(Subscribe.class)) {
                annotated.add(method);
            }
        }
        if (annotated.isEmpty()) {
            throw new InvalidHandler(listener.getClass().getName()
                    + " has no @Subscribe method; registering it would do nothing");
        }
        for (Method method : annotated) {
            add(listener, method);
        }
    }

    private void add(Object listener, Method method) throws InvalidHandler {
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length < 1 || parameters.length > 2) {
            throw new InvalidHandler(describe(method) + " takes " + parameters.length
                    + " parameters; a handler takes an event, and an EventControl if it cancels");
        }
        boolean wantsControl = parameters.length == 2;
        if (wantsControl && parameters[1] != EventControl.class) {
            throw new InvalidHandler(describe(method) + " takes a " + parameters[1].getName()
                    + " as its second parameter; the only thing a handler may ask for beside "
                    + "the event is " + EventControl.class.getName());
        }
        Subscribed subscribed = resolve(method, parameters[0]);
        if (wantsControl && !subscribed.cancellable()) {
            throw new InvalidHandler(describe(method) + " asks for an EventControl, but "
                    + subscribed.type() + " is not cancellable. The tick never waits for it, so "
                    + "a cancel would silently do nothing");
        }
        // Kept so the dispatch can build the event: a plugin-defined one has no
        // generated factory, and this handler's own class is what says how to
        // read the payload.
        if (subscribed.codec() != null) {
            codecs.putIfAbsent(subscribed.type(), subscribed.codec());
        }
        String type = subscribed.type();
        Priority priority = method.getAnnotation(Subscribe.class).priority();
        List<Handler> handlers = byType.computeIfAbsent(type, key -> new ArrayList<>());
        handlers.add(new Handler(listener, method, priority, wantsControl));
        // Ordered here rather than at dispatch: an event that blocks the tick
        // should not pay for a sort it could have done at load.
        handlers.sort(Comparator.comparing(Handler::priority));
    }

    /// Works out which event a handler subscribed to, from the class it takes.
    ///
    /// Two families and one question. A native event is generated from the ABI
    /// schema and the runtime knows it by class; a plugin-defined one is a class
    /// this plugin compiled, and its codec sits beside it. Neither is looked up
    /// by name, so there is no event name to misspell in either case.
    private Subscribed resolve(Method method, Class<?> parameter) throws InvalidHandler {
        String nativeType = GeneratedEvents.typeOf(parameter);
        if (nativeType != null) {
            return new Subscribed(nativeType, GeneratedEvents.cancellable(nativeType), null);
        }
        CustomEvent codec = EventLayouts.of(parameter);
        if (codec == null) {
            throw new InvalidHandler(describe(method) + " takes a " + parameter.getName()
                    + ", which is not an event. A native event belongs to the ABI this runtime "
                    + "was built against, and one this plugin defines needs @PluginEvent with "
                    + "gocraft-apt on the annotation processor path");
        }
        return new Subscribed(codec.eventType(), codec.cancellable(), codec);
    }

    /// The codec for a plugin-defined event this plugin subscribed to, or null
    /// when nothing here handles that type.
    CustomEvent codecFor(String type) {
        return codecs.get(type);
    }

    private boolean alreadyRegistered(Object listener) {
        for (List<Handler> handlers : byType.values()) {
            for (Handler handler : handlers) {
                if (handler.target() == listener) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /// The event types this plugin registered, which the host checks against
    /// the manifest it already validated.
    List<String> types() {
        return List.copyOf(byType.keySet());
    }

    boolean handles(String type) {
        return byType.containsKey(type);
    }

    int size() {
        return byType.values().stream().mapToInt(List::size).sum();
    }

    /// Runs every handler for one event, in priority order.
    ///
    /// A handler that throws does not stop the others: one broken subscriber
    /// must not swallow the decisions of the plugins after it. The failure is
    /// reported and the event carries on with whatever the rest decided.
    void dispatch(String type, Object event, EventControl control, ProblemReporter problems) {
        for (Handler handler : byType.getOrDefault(type, List.of())) {
            try {
                handler.invoke(event, control);
            } catch (InvocationTargetException thrown) {
                problems.report(describe(handler.method()), thrown.getCause());
            } catch (ReflectiveOperationException | RuntimeException | Error thrown) {
                problems.report(describe(handler.method()), thrown);
            }
        }
    }

    @FunctionalInterface
    interface ProblemReporter {
        void report(String handler, Throwable thrown);
    }

    /// Drops every reference to plugin code.
    ///
    /// A Method retains its declaring class, and that retains the classloader.
    /// Without this, unloading would release the plugin's jars and keep every
    /// class it defined — Bukkit's `/reload` leak, one level down.
    void clear() {
        codecs.clear();
        byType.clear();
    }
}