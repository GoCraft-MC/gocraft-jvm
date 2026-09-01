package fr.gocraft.api.command;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Bounds one numeric argument.
///
/// The bounds reach the client: they are rendered into the command graph both
/// editions receive, so a value out of range is refused before it is sent. The
/// host checks them again when it parses, because a console and a non-vanilla
/// client are not bound by what a graph says.
///
/// Defaults are the widest the type allows, so writing only one bound leaves
/// the other open.
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface Range {

    double min() default Double.NEGATIVE_INFINITY;

    double max() default Double.POSITIVE_INFINITY;
}
