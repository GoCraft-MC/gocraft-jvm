package fr.gocraft.apt;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/// Runs javac with the processor, the way a plugin build runs it.
///
/// A processor is only worth what javac does with it, so the test compiles real
/// sources rather than calling methods on the processor: the generated code has
/// to be code, and the only judge of that is the compiler that then compiles
/// it. A bad emitter fails here rather than in whichever plugin hits it first.
final class Javac {

    record Result(List<String> errors, List<Generated> generated, String intermediate) {

        String source(String name) {
            for (Generated file : generated) {
                if (file.name().equals(name)) {
                    return file.source();
                }
            }
            throw new AssertionError("nothing generated for " + name + "; got " + names());
        }

        List<String> names() {
            return generated.stream().map(Generated::name).toList();
        }

        String firstError() {
            return errors.isEmpty() ? "" : errors.getFirst();
        }
    }

    record Generated(String name, String source) {
    }

    static Result compile(String className, String source) throws IOException {
        return compile(className, source, CommandProcessor.class.getName());
    }

    /// The processor is a parameter because this module now has two, and a
    /// test that ran both would report the other one's silence as a missing
    /// generated file.
    static Result compile(String className, String source, String processor) throws IOException {
        Path root = Files.createTempDirectory("apt");
        try {
            return run(root, className, source, processor);
        } finally {
            delete(root);
        }
    }

    /// compileAndLoad keeps the class files, so a test can call what the
    /// processor generated rather than only read it.
    ///
    /// The loader delegates to the parent for fr.gocraft.api, so a CommandSet
    /// it hands back is the same class the test holds — which is the only way
    /// the three facades can be compared to each other at all.
    record Loaded(Result result, URLClassLoader loader, Path root) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            loader.close();
            delete(root);
        }
    }

    static Loaded compileAndLoad(String className, String source) throws IOException {
        Path root = Files.createTempDirectory("apt");
        Result result = run(root, className, source, CommandProcessor.class.getName());
        URLClassLoader loader = new URLClassLoader(
                new URL[] {root.resolve("out").toUri().toURL()}, Javac.class.getClassLoader());
        return new Loaded(result, loader, root);
    }

    private static Result run(Path root, String className, String source, String processor)
            throws IOException {
        {
            Path sources = Files.createDirectories(root.resolve("src"));
            Path classes = Files.createDirectories(root.resolve("out"));
            Path emitted = Files.createDirectories(root.resolve("gen"));

            Path file = sources.resolve(className + ".java");
            Files.writeString(file, source, StandardCharsets.UTF_8);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
            try (StandardJavaFileManager files = compiler.getStandardFileManager(collected, null, null)) {
                List<String> options = List.of(
                        "-classpath", System.getProperty("java.class.path"),
                        "-d", classes.toString(),
                        "-s", emitted.toString(),
                        "-processor", processor);
                compiler.getTask(null, files, collected, options, null,
                        files.getJavaFileObjects(file)).call();
            }

            List<String> errors = new ArrayList<>();
            collected.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .forEach(diagnostic -> errors.add(diagnostic.getMessage(null)));

            List<Generated> generated = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(emitted)) {
                for (Path produced : walk.filter(Files::isRegularFile).toList()) {
                    String name = produced.getFileName().toString().replace(".java", "");
                    generated.add(new Generated(name, Files.readString(produced)));
                }
            }
            Path json = classes.resolve("gocraft/commands.json");
            String intermediate = Files.exists(json) ? Files.readString(json) : "";
            return new Result(errors, generated, intermediate);
        }
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Javac() {
    }
}
