package fr.gocraft.apt;

import fr.gocraft.api.EventValue;
import fr.gocraft.api.PluginEvent;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/// Turns a class an author wrote into the codec the runtime reads it through.
///
/// §10's promise is that a plugin-defined event is an ordinary class: no
/// generated superclass, no interface to implement, no index to keep in step.
/// This is what makes that hold — it reads the class, derives the layout from
/// declaration order and `final`, and writes a codec beside it that nobody ever
/// opens.
///
/// Everything it can catch, it catches while javac is running: a field with no
/// accessor, a mutable field with no setter, a type no runtime can carry. The
/// author sees them underlined rather than as a plugin that loads and then
/// fails to publish anything.
@SupportedAnnotationTypes({"fr.gocraft.api.PluginEvent", "fr.gocraft.api.EventValue"})
public final class EventProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /// Every event seen so far, kept because the dump describes all of them at
    /// once and javac hands them over a round at a time.
    private final List<LayoutDump.Declared> declared = new ArrayList<>();

    /// The records seen so far, by manifest name, with their layouts and the
    /// class each came from.
    ///
    /// Kept because a cycle is a property of the whole graph and javac hands the
    /// classes over a round at a time: a record reaching itself through another
    /// cannot be seen while looking at either one alone.
    private final Map<String, List<Field>> records = new LinkedHashMap<>();
    private final Map<String, String> recordClasses = new LinkedHashMap<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        Diagnostics diagnostics = new Diagnostics(processingEnv.getMessager());
        if (round.processingOver()) {
            // Written last, when there is nothing more to add. A dump per round
            // would describe whichever events that round happened to carry.
            detectCycles(diagnostics);
            writeDump(diagnostics);
            return true;
        }
        // Records first, so an event naming one is resolved against a layout
        // this round has already read rather than against the annotation alone.
        for (Element element : round.getElementsAnnotatedWith(EventValue.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                diagnostics.error("@EventValue declares something an event carries, so it "
                        + "belongs on a class", element);
                continue;
            }
            declareRecord((TypeElement) element, diagnostics);
        }
        for (Element element : round.getElementsAnnotatedWith(PluginEvent.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                diagnostics.error("@PluginEvent declares an event, so it belongs on a class",
                        element);
                continue;
            }
            declare((TypeElement) element, diagnostics);
        }
        return true;
    }

    private void declare(TypeElement type, Diagnostics diagnostics) {
        // Resolved by name — PurchaseEvent gives PurchaseEventLayout in the
        // same package — so a nested class would need a name no top-level file
        // can carry. Refused rather than mangled.
        if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
            diagnostics.error("a plugin event must be a top-level class, so its codec can sit "
                    + "beside it", type);
            return;
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            diagnostics.error("a plugin event is published, so it cannot be abstract", type);
            return;
        }
        PluginEvent declared = type.getAnnotation(PluginEvent.class);
        if (!validEventType(declared.value())) {
            diagnostics.error("\"" + declared.value() + "\" is not a valid event type; it must "
                    + "read namespace/name, e.g. \"fr.oreo.shop/purchase\". The slash is what "
                    + "keeps a plugin event from shadowing a native one", type);
            return;
        }

        List<Field> layout = layoutOf(type, diagnostics);
        if (diagnostics.failed()) {
            return;
        }
        if (!hasLayoutConstructor(type, layout)) {
            diagnostics.error("a plugin event is built from the values that arrive, so it needs "
                    + "`" + type.getSimpleName() + "(" + signature(layout) + ")` — every field in "
                    + "declaration order. Without it a plugin subscribing to this event has "
                    + "nothing to hand its handler", type);
            return;
        }
        write(type, declared, layout, diagnostics);
        this.declared.add(new LayoutDump.Declared(declared, layout));
    }

    /// The positional layout of one class: its instance fields, in declaration
    /// order.
    ///
    /// Shared by events and records because a record is an event's payload one
    /// level down, and the rules are the same rules — order is the wire order,
    /// final means read-only, an accessor is how the codec reads it.
    private List<Field> layoutOf(TypeElement type, Diagnostics diagnostics) {
        List<Field> layout = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) member;
            if (field.getModifiers().contains(Modifier.STATIC)) {
                // A constant is not part of the payload: it has the same value
                // in every instance, so carrying it would be paying per emission
                // for something the subscriber's own code already has.
                continue;
            }
            Field resolved = resolve(type, field, diagnostics);
            if (resolved != null) {
                layout.add(resolved);
            }
        }
        return layout;
    }

    /// Reads one @EventValue class and writes its codec.
    ///
    /// A record has no setFields. What is written back into an author's object
    /// is the event's own fields, one level up; the host applies a deep mutation
    /// against the values rather than against anybody's object.
    private void declareRecord(TypeElement type, Diagnostics diagnostics) {
        if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
            diagnostics.error("an @EventValue class must be top-level, so its codec can sit "
                    + "beside it", type);
            return;
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            diagnostics.error("an @EventValue class is built from what arrives, so it cannot be "
                    + "abstract", type);
            return;
        }
        String qualified = type.getQualifiedName().toString();
        String name = type.getAnnotation(EventValue.class).value();
        if (name.isBlank()) {
            name = qualified;
        }
        if (!validRecordName(name)) {
            diagnostics.error(quoted(name) + " is not a valid record name; it must be a dotted "
                    + "name like fr.oreo.Tier. A slash would make it an event type, which is a "
                    + "different vocabulary", type);
            return;
        }
        List<Field> layout = layoutOf(type, diagnostics);
        if (diagnostics.failed()) {
            return;
        }
        if (layout.isEmpty()) {
            diagnostics.error("an @EventValue class carrying nothing encodes to an empty list "
                    + "and tells a subscriber nothing", type);
            return;
        }
        if (!hasLayoutConstructor(type, layout)) {
            diagnostics.error("an @EventValue class is built from the values that arrive, so it "
                    + "needs `" + type.getSimpleName() + "(" + signature(layout) + ")` — every "
                    + "field in declaration order", type);
            return;
        }
        String previous = recordClasses.putIfAbsent(name, qualified);
        if (previous != null && !previous.equals(qualified)) {
            diagnostics.error(previous + " already declares the record " + name
                    + "; two classes describing one record is the disagreement the manifest "
                    + "exists to prevent", type);
            return;
        }
        records.put(name, layout);
        String generated = qualified + "Values";
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(generated, type);
            try (Writer writer = file.openWriter()) {
                writer.write(new RecordEmitter().render(qualified, layout));
            }
        } catch (IOException failure) {
            diagnostics.error("could not write " + generated + ": " + failure.getMessage(), type);
        }
    }

    /// Refuses a record that contains itself, directly or through another.
    ///
    /// The wire is a finite positional payload with no pointers, so that is not
    /// a shape that could be encoded at all. The manifest refuses it too; saying
    /// it here means the author sees it while compiling rather than at the far
    /// end of a build.
    private void detectCycles(Diagnostics diagnostics) {
        for (String name : records.keySet()) {
            List<String> path = new ArrayList<>();
            if (reaches(name, name, path, new HashSet<>())) {
                diagnostics.error("the record " + name + " contains itself, through "
                        + String.join(" -> ", path) + " -> " + name, null);
                return;
            }
        }
    }

    private boolean reaches(String from, String target, List<String> path, Set<String> seen) {
        if (!seen.add(from)) {
            return false;
        }
        for (Field field : records.getOrDefault(from, List.of())) {
            Carried carried = field.carried() instanceof Carried.Listed listed
                    ? listed.element()
                    : field.carried();
            if (!(carried instanceof Carried.Compound compound)) {
                continue;
            }
            path.add(from);
            if (compound.name().equals(target) || reaches(compound.name(), target, path, seen)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
    }

    /// The same rule the manifest applies, so a name javac accepts is a name the
    /// host accepts. Case is kept: a record is a type, and every language this
    /// contract serves capitalises one.
    private static boolean validRecordName(String name) {
        if (name.isEmpty() || name.startsWith(".") || name.endsWith(".")
                || name.contains("..") || name.indexOf('/') >= 0) {
            return false;
        }
        if (Character.isDigit(name.charAt(0))) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '.' && character != '_') {
                return false;
            }
        }
        return true;
    }

    private static String quoted(String value) {
        return '"' + value + '"';
    }

    /// Hands the layouts to gocraft-cli, which writes them into the manifest.
    ///
    /// Nothing is written when a plugin declares no events, so a build that has
    /// none leaves no artefact for the packer to wonder about.
    private void writeDump(Diagnostics diagnostics) {
        if (declared.isEmpty() && records.isEmpty()) {
            return;
        }
        try {
            FileObject file = processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", LayoutDump.PATH);
            try (Writer writer = file.openWriter()) {
                writer.write(new LayoutDump().render(declared, records));
            }
        } catch (IOException failure) {
            diagnostics.error("could not write " + LayoutDump.PATH + ": " + failure.getMessage(),
                    null);
        }
    }

    /// Whether the event can be rebuilt from a payload.
    ///
    /// The publishing side never needs this: it constructs the event itself,
    /// however it likes. A subscriber does — it holds its own class matching the
    /// provider's layout, and there is no shared type to hand it — so the
    /// requirement is checked for every event rather than only for the ones
    /// somebody happens to subscribe to. An event that gains a subscriber a year
    /// later should not fail then.
    private boolean hasLayoutConstructor(TypeElement owner, List<Field> layout) {
        for (Element member : owner.getEnclosedElements()) {
            if (member.getKind() != ElementKind.CONSTRUCTOR) {
                continue;
            }
            ExecutableElement candidate = (ExecutableElement) member;
            if (candidate.getModifiers().contains(Modifier.PRIVATE)) {
                // The generated codec sits in the same package, so anything
                // else it can call.
                continue;
            }
            List<? extends VariableElement> parameters = candidate.getParameters();
            if (parameters.size() != layout.size()) {
                continue;
            }
            boolean matches = true;
            for (int index = 0; index < layout.size(); index++) {
                if (!parameters.get(index).asType().toString().equals(layout.get(index).declared())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private String signature(List<Field> layout) {
        StringBuilder types = new StringBuilder();
        for (Field field : layout) {
            if (!types.isEmpty()) {
                types.append(", ");
            }
            types.append(field.declared()).append(' ').append(field.name());
        }
        return types.toString();
    }

    /// Works out how one field crosses the wire, or says why it cannot.
    private Field resolve(TypeElement owner, VariableElement field, Diagnostics diagnostics) {
        String name = field.getSimpleName().toString();
        Carried carried = carriedBy(field.asType().toString());
        if (carried == null) {
            diagnostics.error(name + " is a " + field.asType() + ", which an event cannot carry. "
                    + "It holds primitives, String, byte[], PlayerRef, a class marked "
                    + "@EventValue, or a List of any of those. A boxed type is refused because "
                    + "the wire has no null; something richer belongs behind an id the "
                    + "subscriber can look up, since carrying a live object is carrying "
                    + "implementation instead of a fact", field);
            return null;
        }
        if (accessor(owner, name) == null) {
            diagnostics.error(name + " has no accessor. Add `" + field.asType() + " " + name
                    + "()`, which is what the codec reads it through: a private field it "
                    + "cannot reach would make the event publish a zero", field);
            return null;
        }
        boolean mutable = !field.getModifiers().contains(Modifier.FINAL);
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        if (mutable && !hasSetter(owner, setter)) {
            diagnostics.error(name + " is not final, so subscribers may change it, but there is "
                    + "no `void " + setter + "(" + field.asType() + ")` to write it back. Make "
                    + "the field final to declare it read-only, or add the setter", field);
            return null;
        }
        return new Field(name, carried, mutable, setter, field.asType().toString());
    }

    private ExecutableElement accessor(TypeElement owner, String name) {
        for (Element member : owner.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.getSimpleName().contentEquals(name) && method.getParameters().isEmpty()
                    && !method.getModifiers().contains(Modifier.PRIVATE)) {
                return method;
            }
        }
        return null;
    }

    private boolean hasSetter(TypeElement owner, String setter) {
        for (Element member : owner.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.getSimpleName().contentEquals(setter) && method.getParameters().size() == 1
                    && !method.getModifiers().contains(Modifier.PRIVATE)) {
                return true;
            }
        }
        return false;
    }

    private void write(TypeElement type, PluginEvent declared, List<Field> layout,
            Diagnostics diagnostics) {
        String qualified = type.getQualifiedName().toString();
        String generated = qualified + "Layout";
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(generated, type);
            try (Writer writer = file.openWriter()) {
                writer.write(new EventEmitter().render(qualified, declared, layout));
            }
        } catch (IOException failure) {
            diagnostics.error("could not write " + generated + ": " + failure.getMessage(), type);
        }
    }

    /// The same rule the manifest decoder applies, so a type javac accepts is a
    /// type the host accepts.
    private static boolean validEventType(String eventType) {
        int slash = eventType.indexOf('/');
        if (slash <= 0 || slash != eventType.lastIndexOf('/') || slash == eventType.length() - 1) {
            return false;
        }
        return dotted(eventType.substring(0, slash)) && dotted(eventType.substring(slash + 1));
    }

    private static boolean dotted(String name) {
        if (name.startsWith(".") || name.endsWith(".") || name.contains("..")) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean allowed = character == '.' || character == '-' || character == '_'
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9');
            if (!allowed) {
                return false;
            }
        }
        return !name.isEmpty();
    }

    /// One field, and everything the codec needs to move it both ways.
    ///
    /// `declared` is the Java type as written. Reading widens on its own — an
    /// int fits a long — but writing back has to narrow explicitly, so the
    /// codec needs to know what it is narrowing to.
    record Field(String name, Carried carried, boolean mutable, String setter, String declared) {
    }

    /// The one place a Java type becomes something the wire carries.
    ///
    /// Null for anything else, which the caller turns into a message naming the
    /// whole vocabulary — a list of what is allowed beats "unsupported type",
    /// which leaves an author guessing whether their own class could be made to
    /// work.
    private Carried carriedBy(String declared) {
        // One level of list. A list of lists has no author asking for it, and
        // the manifest refuses one for the same reason: it would mean deciding
        // how deep a mutation path may reach before anybody has written one.
        if (declared.startsWith(LIST_PREFIX) && declared.endsWith(">")) {
            String element = declared.substring(LIST_PREFIX.length(), declared.length() - 1);
            Carried inside = simpleCarriedBy(element);
            return inside == null ? null : new Carried.Listed(inside, declared);
        }
        return simpleCarriedBy(declared);
    }

    private static final String LIST_PREFIX = "java.util.List<";

    private Carried simpleCarriedBy(String declared) {
        Kind kind = Kind.of(declared);
        if (kind != null) {
            return new Carried.Scalar(kind, declared);
        }
        if (declared.equals("fr.gocraft.api.PlayerRef")) {
            return new Carried.Player();
        }
        TypeElement type = processingEnv.getElementUtils().getTypeElement(declared);
        if (type == null) {
            return null;
        }
        EventValue value = type.getAnnotation(EventValue.class);
        if (value == null) {
            return null;
        }
        String name = value.value().isBlank() ? declared : value.value();
        return new Carried.Compound(name, declared, declared + "Values");
    }

    /// The scalars an event can carry, and how each is spelled as a [Value].
    /// The scalars an event can carry, and how each is spelled as a Value.
    ///
    /// Boxed types are deliberately absent. A `Integer` field can be null, and
    /// the wire has no null: it would have to become a zero, an absent field or
    /// a refusal, and choosing silently is how a subscriber ends up reading a
    /// price of 0 that nobody set. Refusing by name leaves the decision with
    /// the author.
    enum Kind {
        BOOL("Bool", "bool"),
        INT("Int", "int"),
        DECIMAL("Decimal", "double"),
        TEXT("Text", "string"),
        BYTES("Bytes", "bytes");

        /// The Value record this kind travels as.
        final String record;

        /// How the manifest spells it, which is not always how Java does: a
        /// short and a long are both an int on the wire, and a float and a
        /// double are both a double. The manifest describes what crosses, not
        /// what the author declared.
        final String manifest;

        Kind(String record, String manifest) {
            this.record = record;
            this.manifest = manifest;
        }

        static Kind of(String declared) {
            return switch (declared) {
                case "boolean" -> BOOL;
                case "byte", "short", "int", "long" -> INT;
                case "float", "double" -> DECIMAL;
                case "java.lang.String" -> TEXT;
                case "byte[]" -> BYTES;
                default -> null;
            };
        }

        /// What the codec narrows to on the way back in. A long read out of a
        /// Value.Int has to become the int the field actually holds.
        boolean narrows() {
            return this == INT || this == DECIMAL;
        }
    }
}