package fr.gocraft.apt;

import fr.gocraft.api.PluginEvent;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
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
import javax.tools.JavaFileObject;

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
@SupportedAnnotationTypes("fr.gocraft.api.PluginEvent")
public final class EventProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        Diagnostics diagnostics = new Diagnostics(processingEnv.getMessager());
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

        List<Field> layout = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) member;
            if (field.getModifiers().contains(Modifier.STATIC)) {
                // A constant is not part of the event: it has the same value in
                // every instance, so carrying it would be paying per emission
                // for something the subscriber's own code already has.
                continue;
            }
            Field resolved = resolve(type, field, diagnostics);
            if (resolved != null) {
                layout.add(resolved);
            }
        }
        if (diagnostics.failed()) {
            return;
        }
        write(type, declared, layout, diagnostics);
    }

    /// Works out how one field crosses the wire, or says why it cannot.
    private Field resolve(TypeElement owner, VariableElement field, Diagnostics diagnostics) {
        String name = field.getSimpleName().toString();
        Kind kind = Kind.of(field.asType().toString());
        if (kind == null) {
            diagnostics.error(name + " is a " + field.asType() + ", which an event cannot carry. "
                    + "A plugin event holds primitives, String and byte[]. A boxed type is "
                    + "refused because the wire has no null; something richer belongs behind "
                    + "an id the subscriber can look up, since an event carrying a live object "
                    + "is carrying implementation instead of a fact", field);
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
        return new Field(name, kind, mutable, setter, field.asType().toString());
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
    record Field(String name, Kind kind, boolean mutable, String setter, String declared) {
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
        BOOL("Bool"),
        INT("Int"),
        DECIMAL("Decimal"),
        TEXT("Text"),
        BYTES("Bytes");

        final String record;

        Kind(String record) {
            this.record = record;
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