package fr.gocraft.api.command;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// The path below the command that reaches this method.
///
///     @Sub("sell <price>")        // /shop sell <price>
///     @Sub("admin reload")        // /shop admin reload
///     @Sub("")                    // /shop
///
/// A bare word is a literal; a word in angle brackets is an argument, and its
/// type comes from the method parameter of the same name. Writing the type in
/// the path as well would be a second place it is declared, free to disagree
/// with the signature the compiler already checks.
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Sub {

    /// Literals and `<arguments>`, separated by spaces. Empty means the command
    /// itself runs here.
    String value();
}
