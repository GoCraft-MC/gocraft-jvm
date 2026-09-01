package fr.gocraft.api.command;

/// The one place a node name is judged.
///
/// A name is what a player types, so it cannot hold whitespace or a slash, and
/// it cannot be padded — a literal with a trailing space matches nothing and
/// looks fine in a diff.
final class Names {

    private Names() {
    }

    static String checked(String name, String what) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a " + what + " has no name");
        }
        if (!name.equals(name.trim())) {
            throw new IllegalArgumentException(what + " name " + quoted(name) + " is padded with whitespace");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isWhitespace(character) || character == '/') {
                throw new IllegalArgumentException(
                        what + " name " + quoted(name) + " cannot hold whitespace or a slash");
            }
        }
        return name;
    }

    static String quoted(String value) {
        return "\"" + value + "\"";
    }
}
