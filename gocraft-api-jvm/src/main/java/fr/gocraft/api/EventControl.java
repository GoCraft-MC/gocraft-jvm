package fr.gocraft.api;

/// How a subscriber refuses what an event announced.
///
/// A handler asks for it by declaring it, and only a handler that cancels needs
/// to:
///
///     @Subscribe(priority = Priority.HIGH)
///     void onBlockBreak(BlockBreakEvent event, EventControl control) {
///         if ("minecraft:bedrock".equals(event.block().id())) {
///             control.cancel();
///             event.sendMessage("Bedrock is not yours to break.");
///         }
///     }
///
/// It is a second parameter rather than a method on the event because a
/// plugin-defined event is an ordinary class its author wrote, and nothing can
/// add a method to it — an annotation processor writes code beside a class, not
/// inside it. Native events could have carried one, being generated, but two
/// ways to cancel depending on who wrote the event is a distinction an author
/// would have to learn for no benefit.
///
/// Asking for it on an event that is not cancellable is refused when the
/// listener is registered, which fails the plugin's load. The tick never waits
/// for an observational event, so a cancel that silently did nothing would be
/// worse than not offering one — the same rule the generated events used to
/// state by omitting the method.
///
/// The host decides the outcome once every subscriber has answered or the
/// budget has run out. Cancelling is a vote, not a verdict: a later subscriber
/// sees it through [#cancelled()] and the host arbitrates.
public interface EventControl {

    /// Prevents the action the event announced.
    void cancel();

    /// Whether this handler, or one that ran before it, has cancelled.
    boolean cancelled();
}