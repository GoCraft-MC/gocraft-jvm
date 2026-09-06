package fr.gocraft.apt;

import java.util.List;

/// What an event codec and a record codec both have to write.
///
/// They generate the same code — a value read out of a positional payload, a
/// value written into one — for the same vocabulary, one level apart. Written
/// twice they would agree until somebody fixed a narrowing bug in one of them.
///
/// Not called Emitter: that name belongs to the command facade, which turns
/// annotations back into the builder calls an author could have written. This
/// one writes codecs, and the two have nothing in common but the word.
abstract class ValueEmitter {

    protected final StringBuilder out = new StringBuilder();

    protected void line(int depth, String text) {
        out.append("    ".repeat(depth)).append(text).append('\n');
    }

    protected void blank() {
        out.append('\n');
    }

    // ── Writing a value out ───────────────────────────────────────────────────

    /// One value, as an expression, for everything that is not a list.
    ///
    /// A list needs a loop and therefore statements, which is why [#encodeList]
    /// exists and why a caller has to ask which it is dealing with.
    protected String encodeValue(Carried carried, String source) {
        return switch (carried) {
            case Carried.Scalar scalar -> "new Value." + scalar.kind().record + "(" + source + ")";
            case Carried.Player ignored -> source + ".value()";
            case Carried.Compound compound -> compound.codec() + ".encode(" + source + ")";
            case Carried.Listed ignored ->
                    throw new IllegalStateException("a list is written by encodeList");
            case Carried.Keyed ignored ->
                    throw new IllegalStateException("a map is written by encodeMap");
        };
    }

    /// A list, as statements assigning to a fresh local.
    protected void encodeList(int depth, Carried.Listed listed, String source, String target) {
        line(depth, "java.util.List<Value> " + target + " = new java.util.ArrayList<>();");
        String item = target + "Item";
        line(depth, "for (" + listed.element().java() + " " + item + " : " + source + ") {");
        refuseNull(depth + 1, item, "an element of this list");
        line(depth + 1, target + ".add(" + encodeValue(listed.element(), item) + ");");
        line(depth, "}");
    }

    /// The wire has no null, and a container is where one can hide.
    ///
    /// A bare field cannot be null — boxed types are refused there for exactly
    /// this reason — but a `List<Integer>` or a `Map<String, Tier>` can hold
    /// one, and the author is the only party who can decide what it meant.
    /// Encoding it as a zero would hand a subscriber a price nobody set; an
    /// unboxing NPE would name the codec instead of the value. So it is refused
    /// where it is, in the emitting plugin, before anything crosses.
    protected void refuseNull(int depth, String source, String what) {
        line(depth, "if (" + source + " == null) {");
        line(depth + 1, "throw new IllegalArgumentException(\"" + what
                + " is null, and the wire has no null\");");
        line(depth, "}");
    }

    /// A map, as statements assigning to a fresh local.
    ///
    /// Sorted by key on the way out. The wire has no map, so this is a list of
    /// pairs — and a list has an order, which means an unsorted map would
    /// serialise differently on two runs of the same event. A bundle is
    /// byte-reproducible and a mutation path addresses a position; neither
    /// survives a payload whose order depends on a hash seed.
    protected void encodeMap(int depth, Carried.Keyed keyed, String source, String target) {
        line(depth, "java.util.List<Value> " + target + " = new java.util.ArrayList<>();");
        String keys = target + "Keys";
        line(depth, "java.util.List<String> " + keys + " = new java.util.ArrayList<>("
                + source + ".keySet());");
        line(depth, "java.util.Collections.sort(" + keys + ");");
        String key = target + "Key";
        line(depth, "for (String " + key + " : " + keys + ") {");
        String held = target + "Value";
        line(depth + 1, keyed.value().java() + " " + held + " = " + source + ".get(" + key + ");");
        refuseNull(depth + 1, held, "a value of this map");
        line(depth + 1, target + ".add(new Value.List(List.of(new Value.Text(" + key + "), "
                + encodeValue(keyed.value(), held) + ")));");
        line(depth, "}");
    }

    /// One field, however it is shaped, as an expression the caller can put in a
    /// list. A list or a map leaves its loop behind first.
    protected String encodeField(int depth, EventProcessor.Field field, String source) {
        if (field.carried() instanceof Carried.Listed listed) {
            String local = field.name() + "Values";
            encodeList(depth, listed, source, local);
            return "new Value.List(" + local + ")";
        }
        if (field.carried() instanceof Carried.Keyed keyed) {
            String local = field.name() + "Values";
            encodeMap(depth, keyed, source, local);
            return "new Value.List(" + local + ")";
        }
        return encodeValue(field.carried(), source);
    }

    // ── Reading a value in ────────────────────────────────────────────────────

    /// One value, as statements assigning to a fresh local of the Java type.
    ///
    /// Every one of them is checked. The values arrived from another plugin, so
    /// a kind that does not match means the two compiled against different
    /// versions of the layout — and building anyway would hand the handler a
    /// zero it would read as a real price.
    protected void decodeValue(int depth, Carried carried, String value, String target,
            String where) {
        switch (carried) {
            case Carried.Scalar scalar -> {
                String raw = target + "Raw";
                line(depth, "if (!(" + value + " instanceof Value." + scalar.kind().record
                        + "(" + carriedType(scalar.kind()) + " " + raw + "))) {");
                line(depth + 1, "throw new IllegalArgumentException(\"" + where + " is not a "
                        + scalar.kind().manifest + "\");");
                line(depth, "}");
                line(depth, scalar.java() + " " + target + " = " + narrow(scalar, raw) + ";");
            }
            // Never refused: the host writes an empty list for an event with no
            // acting player, and PlayerRef.of reads that as NONE. A subscriber
            // asking who broke a block a piston broke gets an absent player,
            // which is the answer.
            case Carried.Player ignored -> line(depth, carried.java() + " " + target
                    + " = fr.gocraft.api.PlayerRef.of(" + value + ", sink);");
            case Carried.Compound compound -> line(depth, compound.java() + " " + target
                    + " = " + compound.codec() + ".decode(" + value + ", sink);");
            case Carried.Listed listed -> {
                String raw = target + "Raw";
                line(depth, "if (!(" + value + " instanceof Value.List(List<Value> " + raw
                        + "))) {");
                line(depth + 1, "throw new IllegalArgumentException(\"" + where
                        + " is not a list\");");
                line(depth, "}");
                line(depth, listed.java() + " " + target + " = new java.util.ArrayList<>();");
                String item = target + "Item";
                line(depth, "for (Value " + item + " : " + raw + ") {");
                decodeValue(depth + 1, listed.element(), item, target + "Element",
                        "an element of " + where);
                line(depth + 1, target + ".add(" + target + "Element);");
                line(depth, "}");
            }
            // A LinkedHashMap, so what comes out iterates in the order it
            // arrived — which is sorted, because that is how it was written.
            case Carried.Keyed keyed -> {
                String raw = target + "Raw";
                line(depth, "if (!(" + value + " instanceof Value.List(List<Value> " + raw
                        + "))) {");
                line(depth + 1, "throw new IllegalArgumentException(\"" + where
                        + " is not a map\");");
                line(depth, "}");
                line(depth, keyed.java() + " " + target + " = new java.util.LinkedHashMap<>();");
                String entry = target + "Entry";
                line(depth, "for (Value " + entry + " : " + raw + ") {");
                line(depth + 1, "if (!(" + entry + " instanceof Value.List(List<Value> "
                        + entry + "Pair)) || " + entry + "Pair.size() < 2");
                line(depth + 2, "|| !(" + entry + "Pair.get(0) instanceof Value.Text(String "
                        + entry + "Key))) {");
                line(depth + 2, "throw new IllegalArgumentException(\"an entry of " + where
                        + " is not a key and a value\");");
                line(depth + 1, "}");
                decodeValue(depth + 1, keyed.value(), entry + "Pair.get(1)", target + "Value",
                        "a value of " + where);
                line(depth + 1, target + ".put(" + entry + "Key, " + target + "Value);");
                line(depth, "}");
            }
        }
    }

    /// The type the Value record hands back, which is not always the field's.
    protected static String carriedType(EventProcessor.Kind kind) {
        return switch (kind) {
            case BOOL -> "boolean";
            case INT -> "long";
            case DECIMAL -> "double";
            case TEXT -> "String";
            case BYTES -> "byte[]";
        };
    }

    /// A Value.Int carries a long, and a field that holds an int has to be
    /// narrowed on the way in or the codec would not compile.
    private static String narrow(Carried.Scalar scalar, String source) {
        String target = EventProcessor.Kind.primitiveOf(scalar.java());
        if (scalar.kind().narrows() && !target.equals(carriedType(scalar.kind()))) {
            // Through the primitive, never through the box: a long does not
            // cast to an Integer, it narrows to an int and autoboxes.
            return "(" + target + ") " + source;
        }
        return source;
    }

}
