// The root builds nothing. It carries what the three artefacts have to agree
// on, and nothing else: a fact spelled in three build files is a fact free to
// drift in two of them.

import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

val javaVersion: String by project

val repositoryUrl = "https://github.com/GoCraft-MC/gocraft-jvm"

// The group a plugin author writes. It is fr.gocraft because that is where
// these artefacts are meant to end up, on Maven Central under a namespace
// backed by gocraft.fr; publishing there needs the domain proven, and until it
// is, JitPack serves the same builds under com.github.GoCraft-MC. Declaring
// com.github.* here instead would bake the interim host into the artefact.
val declaredVersion: String by project

allprojects {
    group = "fr.gocraft"
    version = declaredVersion

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
            // A plugin author reads these types more than they read the
            // javadoc for them, and an IDE that can step into the API is worth
            // more than the few kilobytes.
            withSourcesJar()
        }
    }

    // What every published artefact says about where it came from. The name and
    // the description are the artefact's own business and live in its build
    // file; everything here is a fact about the repository, and a fact spelled
    // three times is a fact free to drift in two of them.
    plugins.withType<MavenPublishPlugin>().configureEach {
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    url = repositoryUrl
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                    developers {
                        developer {
                            id = "Traqueur"
                            name = "Traqueur_"
                        }
                    }
                    scm {
                        url = repositoryUrl
                        connection = "scm:git:$repositoryUrl.git"
                        developerConnection = "scm:git:ssh://git@github.com/GoCraft-MC/gocraft-jvm.git"
                    }
                }
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
