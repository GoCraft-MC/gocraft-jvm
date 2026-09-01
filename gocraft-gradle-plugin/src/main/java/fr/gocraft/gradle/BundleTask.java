package fr.gocraft.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/// Assembles a bundle and hands it to gocraft-cli.
///
/// Two jobs, and only the first is this task's opinion. A JVM bundle has a
/// shape — plugin.toml at the root, jars under payload/ — and that shape is the
/// runtime's, so it is staged here. What a bundle *is* stays gocraft-cli's:
/// this never writes a zip, never encodes a command tree, and never decides
/// what a manifest may say.
///
/// The staging directory is why. gocraft-cli packs a directory as it finds it,
/// so the layout has to exist somewhere before it runs, and a Gradle project is
/// laid out for javac rather than for a server.
public abstract class BundleTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getTool();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getManifest();

    /// The jars that go under payload/: the plugin's own, and its dependencies
    /// unless the author turned them off.
    @InputFiles
    public abstract ConfigurableFileCollection getPayload();

    /// What gocraft-apt wrote while javac compiled. Absent for a plugin that
    /// declares no commands, which is most of them.
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCommands();

    @OutputFile
    public abstract RegularFileProperty getBundle();

    /// Where the layout is built before the packer sees it.
    ///
    /// Not an output: nothing downstream reads it, and its content is a copy of
    /// this task's inputs in the shape the runtime expects. The task empties it
    /// each run rather than letting Gradle track it, so a jar removed from the
    /// payload cannot linger in a bundle.
    @Internal
    public abstract DirectoryProperty getStaging();

    @Inject
    protected abstract ExecOperations getExec();

    @TaskAction
    public void pack() {
        Path staging = getStaging().get().getAsFile().toPath();
        stage(staging);

        List<String> arguments = new ArrayList<>();
        arguments.add("build");
        arguments.add("-o");
        arguments.add(getBundle().get().getAsFile().getAbsolutePath());
        if (getCommands().isPresent() && getCommands().get().getAsFile().isFile()) {
            arguments.add("-commands");
            arguments.add(getCommands().get().getAsFile().getAbsolutePath());
        }
        arguments.add(staging.toAbsolutePath().toString());

        getExec().exec(spec -> {
            spec.setExecutable(getTool().get().getAsFile().getAbsolutePath());
            spec.setArgs(arguments);
        });
    }

    private void stage(Path staging) {
        try {
            deleteTree(staging);
            Files.createDirectories(staging.resolve("payload"));

            File manifest = getManifest().get().getAsFile();
            if (!manifest.isFile()) {
                throw new GradleException("no plugin.toml at " + manifest
                        + ". A bundle needs one: it is what tells the server your id, your"
                        + " entry class and what you subscribe to.");
            }
            Files.copy(manifest.toPath(), staging.resolve("plugin.toml"),
                    StandardCopyOption.REPLACE_EXISTING);

            for (File jar : getPayload()) {
                if (!jar.isFile()) {
                    continue;
                }
                Files.copy(jar.toPath(), staging.resolve("payload").resolve(jar.getName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
