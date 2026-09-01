package fr.gocraft.api.command;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Declares a class as one command.
///
///     @Cmd("shop") @Permission("shop.use")
///     public final class ShopCommands {
///         @Sub("sell <price>")
///         void sell(CommandSender sender, @Range(min = 0.01) double price) { … }
///     }
///
/// This inverts a useful prejudice. In Bukkit, annotation frameworks are the
/// slow, fragile ones; here they are the fastest path. The tree is static, so
/// the build writes it into the bundle and the host knows the commands before
/// this JVM launches — and a malformed one is a compile error in the IDE rather
/// than a line in a server log.
///
/// Named Cmd rather than Command because Command is the builder this compiles
/// to, and one import should not shadow the other in a file that uses both.
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Cmd {

    /// The word a player types, without the slash.
    String value();
}
