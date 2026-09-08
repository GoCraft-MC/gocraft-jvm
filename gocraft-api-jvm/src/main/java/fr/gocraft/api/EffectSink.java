package fr.gocraft.api;

import java.util.List;

/// Where a handle's effects go.
///
/// One per dispatched event, handed to the event at decode so that every handle
/// in its payload can act without being given a channel to act through. That is
/// the whole point: an effect belongs to the thing it happens to, so a message
/// is `player.sendMessage(…)` and not a call on some channel that takes the
/// player as an argument. The channel would grow a method per verb; this way
/// the vocabulary grows on its own.
///
/// A plugin never names this type. It is what [EventControl] and the generated
/// events pass around, and what a handle holds.
public interface EffectSink {

    /// Queues one host call, or explains why it cannot be queued.
    ///
    /// Refusing rather than dropping: a sink is sealed once its verdict has
    /// been sent, and a handle kept past that point would otherwise ask for
    /// something nobody would ever perform, silently.
    void add(String call, List<Value> values);
}