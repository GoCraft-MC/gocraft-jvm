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
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.Internal;
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

    /// What gocraft-apt wrote while javac compiled.
    ///
    /// A collection rather than a file, because most plugins declare no
    /// commands and there is then nothing at that path. An @Optional
    /// RegularFileProperty does not cover it: optional means the property may
    /// be unset, and this one is set — to a file the processor had no reason to
    /// write. A file collection is allowed to be empty, which is what "no
    /// commands" actually is.
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCommands();

    /// The event layouts gocraft-apt wrote, for the same reason and in the same
    /// shape: most plugins define no events, and a collection is allowed to be
    /// empty where an optional file property is not.
    ///
    /// The packer merges them into the manifest it writes into the bundle, so
    /// the events a plugin defines are described by the classes the compiler
    /// saw rather than by a block the author kept in step by hand.
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getEvents();

    /// The record of what this plugin's events looked like when it was last
    /// built, kept in the project and meant to be committed.
    ///
    /// The packer compares the layouts the compiler just extracted against it
    /// and refuses a reordered or removed field: the index is what the wire
    /// carries, so swapping two fields hands every subscriber already compiled
    /// against the old layout the wrong one, silently and with nothing anywhere
    /// saying so. Appending is allowed.
    ///
    /// It points at the project and not at the staging directory, which this
    /// task empties on every run — a record written there would be gone before
    /// the next build could read it.
    ///
    /// An optional **input**, and neither an output nor internal. Both of the
    /// other two are wrong in a way that was measured rather than reasoned
    /// about: as an @OutputFile Gradle deletes it when the task is out of date,
    /// and deleting the record is precisely what must not happen, since the
    /// build after that accepts anything; as @Internal it is not part of the
    /// up-to-date check, so editing the record does not re-run the task and the
    /// comparison silently does not happen.
    ///
    /// The task does write it, which makes this an input the task also updates.
    /// That is honest rather than tidy: the next build's answer genuinely
    /// depends on what this one recorded.
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getLayoutLock();

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
        for (File declared : getCommands()) {
            if (declared.isFile()) {
                arguments.add("-commands");
                arguments.add(declared.getAbsolutePath());
                break;
            }
        }
        for (File declared : getEvents()) {
            if (declared.isFile()) {
                arguments.add("-events");
                arguments.add(declared.getAbsolutePath());
                if (getLayoutLock().isPresent()) {
                    arguments.add("-layout-lock");
                    arguments.add(getLayoutLock().get().getAsFile().getAbsolutePath());
                }
                break;
            }
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
