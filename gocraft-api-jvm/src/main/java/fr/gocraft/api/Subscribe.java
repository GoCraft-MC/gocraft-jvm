package fr.gocraft.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks a method as an event handler.
///
///     @Subscribe(priority = Priority.HIGH)
///     public void onBlockBreak(BlockBreakEvent e) {
///         if (e.pos().distanceSq(SPAWN) < 4096 && !e.can("spawn.bypass")) {
///             e.cancel();
///             e.player().sendMessage("Protected area.");
///         }
///     }
///
/// **The parameter type is the subscription.** There is no event name here to
/// misspell: the method takes a generated event class, and that class carries
/// the type the host routes on. A handler for a class this runtime does not
/// know is refused at load rather than never being called.
///
/// The type must also appear in the manifest. The host decides who receives
/// what from the manifest alone — that is what lets it skip serialising an
/// event nobody subscribed to, and build the command packet before this JVM has
/// started.
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {

    /// Where this handler runs among the others.
    ///
    /// Ordering is the host's, across every runtime at once: subscribers run in
    /// priority order, then by plugin id, and they share one budget for the
    /// whole event. A handler at MONITOR sees what everyone else decided and
    /// must not change it.
    Priority priority() default Priority.NORMAL;
}