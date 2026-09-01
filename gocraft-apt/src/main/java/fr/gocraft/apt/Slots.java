package fr.gocraft.apt;

import fr.gocraft.api.command.Greedy;
import fr.gocraft.api.command.Range;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/// Reads an argument's type off the method signature.
///
/// The signature is the declaration. Writing the type in the @Sub path as well
/// would be a second place it lives, free to disagree with the one the compiler
/// already checks — and the compiler is the half that cannot be wrong.
final class Slots {

    static final String SENDER = "fr.gocraft.api.CommandSender";

    private final Types types;

    Slots(Types types) {
        this.types = types;
    }

    /// of derives what the tree declares and how the invoker reads it back.
    ///
    /// Returns null when the type has no representation, having already said so
    /// through the messager: one diagnostic, on the parameter, in the IDE.
    Slot of(VariableElement parameter, Diagnostics diagnostics) {
        String name = parameter.getSimpleName().toString();
        TypeMirror mirror = parameter.asType();
        String declared = types.erasure(mirror).toString();
        Range range = parameter.getAnnotation(Range.class);
        boolean greedy = parameter.getAnnotation(Greedy.class) != null;

        if (greedy && !declared.equals("java.lang.String")) {
            diagnostics.error("@Greedy takes the rest of the line, so it only fits a String", parameter);
            return null;
        }
        if (range != null && !isNumeric(declared)) {
            diagnostics.error("@Range bounds a number, and " + declared + " is not one", parameter);
            return null;
        }

        return switch (declared) {
            case "int", "java.lang.Integer" -> new Slot(name, integerType(range),
                    "(int) context.number(\"" + name + "\")", false, bounded("integer", range));
            case "long", "java.lang.Long" -> new Slot(name, integerType(range),
                    "context.number(\"" + name + "\")", false, bounded("integer", range));
            case "double", "java.lang.Double" -> new Slot(name, decimalType(range),
                    "context.decimal(\"" + name + "\")", false, bounded("decimal", range));
            case "float", "java.lang.Float" -> new Slot(name, decimalType(range),
                    "(float) context.decimal(\"" + name + "\")", false, bounded("decimal", range));
            case "java.lang.String" -> new Slot(name,
                    greedy ? "ArgType.greedy()" : "ArgType.string()",
                    "context.text(\"" + name + "\")", greedy, kind(greedy ? "greedy" : "string"));
            case "fr.gocraft.api.PlayerRef" -> new Slot(name, "ArgType.player()",
                    "context.player(\"" + name + "\")", false, kind("player"));
            case "fr.gocraft.api.BlockPos" -> new Slot(name, "ArgType.blockPos()",
                    "context.position(\"" + name + "\")", false, kind("block_pos"));
            case "fr.gocraft.api.Block" -> new Slot(name, "ArgType.blockState()",
                    "context.block(\"" + name + "\")", false, kind("block_state"));
            case "fr.gocraft.api.ItemRef" -> new Slot(name, "ArgType.item()",
                    "context.item(\"" + name + "\")", false, kind("item"));
            case "java.time.Duration" -> new Slot(name, "ArgType.duration()",
                    "context.duration(\"" + name + "\")", false, kind("duration"));
            default -> enumeration(parameter, name, declared, mirror, diagnostics);
        };
    }

    /// An enum parameter becomes the enum of its own constants.
    ///
    /// The author already wrote the option list, as a Java type. Making them
    /// repeat it in an annotation would be the same list twice, and the day one
    /// gained a constant the other would quietly not.
    private Slot enumeration(VariableElement parameter, String name, String declared,
            TypeMirror mirror, Diagnostics diagnostics) {
        Element element = types.asElement(mirror);
        if (element == null || element.getKind() != ElementKind.ENUM) {
            diagnostics.error(declared + " is not something a command argument can carry; "
                    + "the set is integer, decimal, string, player, block position, block, item, "
                    + "duration and any enum", parameter);
            return null;
        }
        List<String> constants = element.getEnclosedElements().stream()
                .filter(member -> member.getKind() == ElementKind.ENUM_CONSTANT)
                .map(member -> member.getSimpleName().toString())
                .toList();
        if (constants.isEmpty()) {
            diagnostics.error(declared + " has no constants, so it completes nothing", parameter);
            return null;
        }
        StringBuilder options = new StringBuilder("ArgType.oneOf(");
        for (int index = 0; index < constants.size(); index++) {
            if (index > 0) {
                options.append(", ");
            }
            options.append('"').append(constants.get(index).toLowerCase(java.util.Locale.ROOT)).append('"');
        }
        options.append(')');
        String read = declared + ".valueOf(context.text(\"" + name
                + "\").toUpperCase(java.util.Locale.ROOT))";
        StringBuilder json = new StringBuilder("\"kind\": \"enum\", \"options\": [");
        for (int index = 0; index < constants.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            json.append('"').append(constants.get(index).toLowerCase(java.util.Locale.ROOT)).append('"');
        }
        json.append(']');
        return new Slot(name, options.toString(), read, false, json.toString());
    }

    /// kind is the neutral description of a type that carries no constraint.
    private static String kind(String name) {
        return "\"kind\": \"" + name + "\"";
    }

    /// bounded is the same, with whichever bounds were declared. A bound left
    /// out is absent rather than saturated: "open above" and "at most the
    /// largest long" are not the same statement.
    private static String bounded(String name, Range range) {
        StringBuilder json = new StringBuilder(kind(name));
        if (range != null && !Double.isInfinite(range.min())) {
            json.append(", \"min\": ").append(number(name, range.min()));
        }
        if (range != null && !Double.isInfinite(range.max())) {
            json.append(", \"max\": ").append(number(name, range.max()));
        }
        return json.toString();
    }

    private static String number(String kind, double value) {
        return kind.equals("integer") ? String.valueOf((long) value) : String.valueOf(value);
    }

    /// Bounds are emitted as the record rather than the factory, because only
    /// the record can say "bounded below and open above".
    private static String integerType(Range range) {
        if (range == null) {
            return "ArgType.integer()";
        }
        return "new ArgType.Integer(" + bound(range.min(), true) + ", " + bound(range.max(), false) + ")";
    }

    private static String decimalType(Range range) {
        if (range == null) {
            return "ArgType.decimal()";
        }
        return "new ArgType.Decimal(" + decimalBound(range.min()) + ", " + decimalBound(range.max()) + ")";
    }

    private static String bound(double value, boolean minimum) {
        if (Double.isInfinite(value)) {
            return "null";
        }
        return (long) value + "L";
    }

    private static String decimalBound(double value) {
        if (Double.isInfinite(value)) {
            return "null";
        }
        return value + "d";
    }

    private static boolean isNumeric(String declared) {
        return switch (declared) {
            case "int", "java.lang.Integer", "long", "java.lang.Long",
                 "double", "java.lang.Double", "float", "java.lang.Float" -> true;
            default -> false;
        };
    }
}
