package fr.gocraft.api.command;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks a String argument as the rest of the line.
///
/// Without it a String argument is one word. The distinction is not cosmetic:
/// a greedy argument consumes everything after it, so nothing may follow it,
/// and both client renderers need to know which one they are drawing.
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface Greedy {
}
