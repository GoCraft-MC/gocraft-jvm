package fr.gocraft.apt;

import fr.gocraft.api.command.Cmd;
import fr.gocraft.api.command.CommandPath;
import fr.gocraft.api.command.Permission;
import fr.gocraft.api.command.Sub;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
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
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.JavaFileObject;

/// Turns an annotated class into the command tree it describes.
///
/// It runs inside javac, which is why it is a separate artefact: it exists
/// while a plugin compiles and never afterwards. What it produces is builder
/// calls — the annotations are a shorthand for the canonical facade, not a
/// second description of a command.
///
/// Everything it can catch, it catches here: a path that names an argument the
/// method does not take, a greedy argument with something after it, two methods
/// claiming one path, a parameter of a type no edition can render. The author
/// sees them underlined, not in a server log.
@SupportedAnnotationTypes("fr.gocraft.api.command.Cmd")
public final class CommandProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        Diagnostics diagnostics = new Diagnostics(processingEnv.getMessager());
        Slots slots = new Slots(processingEnv.getTypeUtils());
        for (Element element : round.getElementsAnnotatedWith(Cmd.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                diagnostics.error("@Cmd declares a command, so it belongs on a class", element);
                continue;
            }
            declare((TypeElement) element, slots, diagnostics);
        }
        return true;
    }

    private void declare(TypeElement type, Slots slots, Diagnostics diagnostics) {
        String name = type.getAnnotation(Cmd.class).value();
        Tree root;
        try {
            root = Tree.literal(name);
        } catch (IllegalArgumentException invalid) {
            diagnostics.error(invalid.getMessage(), type);
            return;
        }
        Permission guard = type.getAnnotation(Permission.class);
        if (guard != null) {
            root.guard(guard.value());
        }

        List<ExecutableElement> methods = subcommands(type);
        if (methods.isEmpty()) {
            diagnostics.error("@Cmd " + name + " has no @Sub method, so it runs nothing", type);
            return;
        }
        for (ExecutableElement method : methods) {
            attach(root, method, slots, diagnostics);
        }
        if (diagnostics.failed()) {
            return;
        }
        write(type, root, diagnostics);
    }

    private List<ExecutableElement> subcommands(TypeElement type) {
        List<ExecutableElement> methods = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD && member.getAnnotation(Sub.class) != null) {
                methods.add((ExecutableElement) member);
            }
        }
        return methods;
    }

    /// attach walks one method's path into the tree and binds its call.
    private void attach(Tree root, ExecutableElement method, Slots slots, Diagnostics diagnostics) {
        if (method.getModifiers().contains(Modifier.PRIVATE)) {
            diagnostics.error("a @Sub method is called from generated code beside it, "
                    + "so it cannot be private", method);
            return;
        }
        if (method.getModifiers().contains(Modifier.STATIC)) {
            diagnostics.error("a @Sub method runs against the instance it was registered with, "
                    + "so it cannot be static", method);
            return;
        }

        CommandPath path;
        try {
            path = CommandPath.parse(method.getAnnotation(Sub.class).value());
        } catch (IllegalArgumentException invalid) {
            diagnostics.error(invalid.getMessage(), method);
            return;
        }

        Map<String, Slot> declared = new LinkedHashMap<>();
        List<String> call = new ArrayList<>();
        List<? extends VariableElement> parameters = method.getParameters();
        for (int index = 0; index < parameters.size(); index++) {
            VariableElement parameter = parameters.get(index);
            if (processingEnv.getTypeUtils().erasure(parameter.asType()).toString().equals(Slots.SENDER)) {
                if (index != 0) {
                    diagnostics.error("the sender comes first or not at all", parameter);
                    return;
                }
                call.add("context.sender()");
                continue;
            }
            Slot slot = slots.of(parameter, diagnostics);
            if (slot == null) {
                return;
            }
            declared.put(slot.name(), slot);
            call.add(slot.read());
        }

        for (String argument : path.arguments()) {
            if (!declared.containsKey(argument)) {
                diagnostics.error("the path names <" + argument + "> and the method takes no "
                        + "parameter called " + argument, method);
                return;
            }
        }
        for (String parameter : declared.keySet()) {
            if (!path.arguments().contains(parameter)) {
                diagnostics.error("the method takes " + parameter + " and the path never asks for <"
                        + parameter + ">", method);
                return;
            }
        }

        Tree node = root;
        try {
            List<CommandPath.Segment> segments = path.segments();
            for (int index = 0; index < segments.size(); index++) {
                CommandPath.Segment segment = segments.get(index);
                if (node.greedy) {
                    diagnostics.error("<" + node.name + "> takes the rest of the line, "
                            + "so nothing may follow it", method);
                    return;
                }
                if (segment instanceof CommandPath.Slot slot) {
                    Slot argument = declared.get(slot.name());
                    node = node.child(slot.name(), true, argument.type());
                    node.greedy = argument.greedy();
                    continue;
                }
                node = node.child(segment.name(), false, null);
            }
            Permission guard = method.getAnnotation(Permission.class);
            if (guard != null) {
                if (node.argument) {
                    diagnostics.error("a permission guards a literal, not a value; "
                            + "guard the branch above <" + node.name + ">", method);
                    return;
                }
                node.guard(guard.value());
            }
            node.runs("target." + method.getSimpleName() + "(" + String.join(", ", call) + ")");
        } catch (Tree.Conflict conflict) {
            diagnostics.error(conflict.getMessage(), method);
        }
    }

    private void write(TypeElement type, Tree root, Diagnostics diagnostics) {
        String qualified = type.getQualifiedName().toString();
        String simple = type.getSimpleName().toString();
        int separator = qualified.lastIndexOf('.');
        String packageName = separator < 0 ? "" : qualified.substring(0, separator);
        String generated = simple + "Tree";
        String source = new Emitter().render(packageName, generated, simple, root);
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(
                    packageName.isEmpty() ? generated : packageName + "." + generated, type);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException failure) {
            diagnostics.error("could not write " + generated + ": " + failure.getMessage(), type);
        }
    }
}
