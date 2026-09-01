// The root builds nothing. It carries what the three artefacts have to agree
// on, and nothing else: a fact spelled in three build files is a fact free to
// drift in two of them.

val javaVersion: String by project

allprojects {
    group = "fr.gocraft"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    // Java 25 is the baseline, not a preference. Scoped values are final in 25
    // (JEP 506) and replace the setContextClassLoader try/finally dance the
    // dispatch path would otherwise need, and synchronized stopped pinning
    // virtual threads in 24 — both are load-bearing for what this runtime does.
    plugins.withType<JavaPlugin>().configureEach {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(javaVersion)
            }
        }
    }

    // Byte-identical across machines and runs, for the same reason gocraft-cli
    // stamps every .gcpkg entry with a fixed 1980 epoch.
    //
    // A jar is a zip, and a zip stores a modification time per entry, so the
    // default output differs on every build even when nothing changed. That is
    // not cosmetic here: the GoCraft host extracts the runtime jar under a name
    // derived from its content hash, precisely so a server can never pick up
    // the jar a previous version left behind. Non-reproducible bytes would mean
    // a fresh cache entry per build, and a release that cannot be verified
    // against its source.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
