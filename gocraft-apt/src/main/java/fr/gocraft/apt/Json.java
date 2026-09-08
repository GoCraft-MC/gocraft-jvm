package fr.gocraft.apt;

/// Writing the little JSON this processor hands to gocraft-cli.
///
/// One escaper, because there were two: the command trees and the event layouts
/// each had their own, character for character the same. A fix in one — a
/// backspace, a form feed, a surrogate pair — would have left the other
/// emitting JSON gocraft-cli cannot parse, and the failure surfaces as "cannot
/// read the dump" rather than as an escaping bug.
final class Json {

    private Json() {
    }

    /// A JSON string.
    ///
    /// Names and types have been through the processor, which accepts
    /// identifiers and dotted names only, so nothing needs escaping today. Done
    /// anyway, because "the input cannot contain a quote" stays true until it
    /// does not.
    static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}