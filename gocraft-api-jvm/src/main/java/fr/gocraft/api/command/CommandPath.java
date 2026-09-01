package fr.gocraft.api.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// The path an @Sub declares, read once.
///
/// It lives beside the annotations rather than inside the processor so the
/// grammar has one definition. The processor reads a path at compile time and a
/// test reads the same path with the same code; a grammar restated in the
/// processor would be a second definition of what `sell <price>` means.
public record CommandPath(List<Segment> segments) {

    /// A step along the path.
    public sealed interface Segment {
        String name();
    }

    /// A word a player types as written.
    public record Word(String name) implements Segment {
        public Word {
            name = Names.checked(name, "literal");
        }
    }

    /// A value, named after the method parameter that gives it its type.
    public record Slot(String name) implements Segment {
        public Slot {
            name = Names.checked(name, "argument");
        }
    }

    public CommandPath {
        segments = List.copyOf(segments);
        Set<String> slots = new HashSet<>();
        for (Segment segment : segments) {
            if (segment instanceof Slot && !slots.add(segment.name())) {
                throw new IllegalArgumentException(
                        "path names the argument " + Names.quoted(segment.name()) + " twice");
            }
        }
    }

    /// parse reads "sell <price>": bare words are literals, angle brackets are
    /// arguments. An empty path means the command itself runs.
    public static CommandPath parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("a command path cannot be null");
        }
        List<Segment> segments = new ArrayList<>();
        for (String token : raw.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            boolean opens = token.startsWith("<");
            boolean closes = token.endsWith(">");
            if (opens != closes) {
                throw new IllegalArgumentException(
                        "path segment " + Names.quoted(token) + " has an unbalanced angle bracket");
            }
            if (opens) {
                segments.add(new Slot(token.substring(1, token.length() - 1)));
                continue;
            }
            segments.add(new Word(token));
        }
        return new CommandPath(segments);
    }

    /// The arguments this path declares, in the order they are typed.
    public List<String> arguments() {
        List<String> names = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment instanceof Slot slot) {
                names.add(slot.name());
            }
        }
        return List.copyOf(names);
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (Segment segment : segments) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(segment instanceof Slot ? "<" + segment.name() + ">" : segment.name());
        }
        return text.toString();
    }
}
