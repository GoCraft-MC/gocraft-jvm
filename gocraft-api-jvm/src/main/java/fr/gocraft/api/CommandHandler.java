package fr.gocraft.api;

/// What runs when somebody types a command.
///
/// The unit §07 calls the invoker. The tree is data — built at bundle time,
/// shipped inside the bundle, read by the host so it can offer completions to a
/// Java client and to a Bedrock one before this JVM has even started — and this
/// is the only part of a command that is code. A lambda does not cross a
/// process boundary, which is why the two are separated at all.
///
/// It is registered against the path through the tree, not against the
/// executor id the tree assigns:
///
///     public void enable() {
///         host.registerCommand("shop sell <price>", ctx -> {
///             if (!ctx.sender().can("shop.sell")) {
///                 ctx.reply("You cannot sell here.");
///                 return;
///             }
///             sell(ctx.sender().player(), ctx.decimal("price"));
///         });
///     }
///
/// Ids are assigned by whatever built the tree; naming one here would be a
/// second place they are written down, free to disagree with the first.
@FunctionalInterface
public interface CommandHandler {

    /// Runs one invocation.
    ///
    /// Throwing is a legitimate way to fail: the message reaches whoever typed
    /// the command and the server log, so it should read like a sentence — "no
    /// region named spawn" rather than a class name. Anything thrown is caught
    /// by the runtime; a handler never takes the process down.
    void handle(CommandContext context) throws Exception;
}