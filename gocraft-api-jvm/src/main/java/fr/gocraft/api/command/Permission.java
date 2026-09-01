package fr.gocraft.api.command;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// The node a sender must hold to reach this command or branch.
///
/// On the class it guards the whole command; on a method it guards the last
/// literal of that method's path. A guarded branch is not merely refused — the
/// host prunes it out of the command list it sends, so a player who cannot use
/// it never learns it exists.
///
/// It cannot guard an argument: an argument is a value, and refusing a value is
/// the handler's business. A path ending in an argument is guarded at the
/// literal above it.
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Permission {

    String value();
}
