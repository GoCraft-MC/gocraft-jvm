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
            case Carried.Player ignored -> "playerValue(" + source + ")";
            case Carried.Compound compound -> compound.codec() + ".encode(" + source + ")";
            case Carried.Listed ignored ->
                    throw new IllegalStateException("a list is written by encodeList");
        };
    }

    /// A list, as statements assigning to a fresh local.
    protected void encodeList(int depth, Carried.Listed listed, String source, String target) {
        line(depth, "java.util.List<Value> " + target + " = new java.util.ArrayList<>();");
        String item = target + "Item";
        line(depth, "for (" + listed.element().java() + " " + item + " : " + source + ") {");
        line(depth + 1, target + ".add(" + encodeValue(listed.element(), item) + ");");
        line(depth, "}");
    }

    /// One field, however it is shaped, as an expression the caller can put in a
    /// list. A list leaves its loop behind first.
    protected String encodeField(int depth, EventProcessor.Field field, String source) {
        if (field.carried() instanceof Carried.Listed listed) {
            String local = field.name() + "Values";
            encodeList(depth, listed, source, local);
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
        if (scalar.kind().narrows() && !scalar.java().equals(carriedType(scalar.kind()))) {
            return "(" + scalar.java() + ") " + source;
        }
        return source;
    }

    // ── The PlayerRef helper, written only where it is used ───────────────────

    protected static boolean carriesAPlayer(List<EventProcessor.Field> layout) {
        for (EventProcessor.Field field : layout) {
            Carried carried = field.carried() instanceof Carried.Listed listed
                    ? listed.element()
                    : field.carried();
            if (carried instanceof Carried.Player) {
                return true;
            }
        }
        return false;
    }

    /// The PlayerRef shape the host reads back: uuid, username, edition.
    ///
    /// Written into the codec rather than called from the API, because the API
    /// jar a plugin compiles against has no encoder — it deliberately has no
    /// dependency at all, not even protobuf, so that a plugin cannot reach the
    /// transport.
    protected void writePlayerHelper() {
        blank();
        line(1, "private static Value playerValue(fr.gocraft.api.PlayerRef player) {");
        line(2, "java.nio.ByteBuffer uuid = java.nio.ByteBuffer.allocate(16);");
        line(2, "uuid.putLong(player.uuid().getMostSignificantBits());");
        line(2, "uuid.putLong(player.uuid().getLeastSignificantBits());");
        line(2, "return new Value.List(List.of(");
        line(3, "new Value.Bytes(uuid.array()),");
        line(3, "new Value.Text(player.username()),");
        line(3, "new Value.Text(switch (player.edition()) {");
        line(4, "case JAVA -> \"java\";");
        line(4, "case BEDROCK -> \"bedrock\";");
        line(4, "case UNKNOWN -> \"\";");
        line(3, "})));");
        line(1, "}");
    }
}