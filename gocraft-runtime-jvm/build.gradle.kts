import java.security.MessageDigest

// The host: the jar the GoCraft server extracts and spawns to run JVM plugins.
//
// No plugin depends on this. It sits on the other side of the boundary from
// gocraft-api-jvm, which it implements — Host, the loader, the codecs — and
// speaks the ABI, which is why protobuf is here and not there.

plugins {
    java
    `maven-publish`
}

// Must equal the plugin version pinned in buf.gen.yaml. The generated sources
// are committed, so the runtime on the classpath and the compiler that emitted
// them are two halves of one decision: gencode from a newer protobuf calls
// methods an older runtime does not have, and it surfaces as "cannot find
// symbol" in files nobody wrote.
val protobufVersion = "4.36.0"

val junitVersion: String by project

dependencies {
    // The API this runtime hosts: Host, the loader and the codecs implement it.
    // Nothing depends on this project in turn — the server spawns the jar, it
    // does not compile against it — so there is no api() to expose.
    implementation(project(":gocraft-api-jvm"))

    // The lite runtime. It gives up reflection and the text format, which a
    // runtime speaking a fixed set of envelopes never uses, and costs about a
    // tenth of what full protobuf-java would against a 3 MB budget.
    implementation("com.google.protobuf:protobuf-javalite:$protobufVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Generated ABI sources are committed, so a contributor needs neither buf nor a
// GoCraft checkout to build. They are a source root rather than a build output
// for the same reason.
sourceSets {
    main {
        java {
            srcDir("src/main/generated")
        }
    }
}

// abiSchema points at the directory holding abi/v1/*.proto. It defaults to the
// sibling gocraft-abi checkout, which is where deliverable 01 put the schema.
//
// Resolved against the root rather than this module, so a relative path means
// what the default reads as: ../gocraft-abi is gocraft-jvm's sibling, not
// gocraft-runtime-jvm's. Project.file() resolved it one directory too deep, so
// the task could only ever run with -PabiSchema — which went unnoticed because
// nothing makes it run.
val abiSchema: String by project
val schemaDirectory = rootProject.file(abiSchema)

// The two schemas this module generates from. envelope.proto imports
// commands.proto and imports nothing else, so these two are the whole input:
// events.proto and options.proto are read by protoc-gen-gocraft to shape the
// generated event classes and are never serialised.
val abiSources = listOf("abi/v1/envelope.proto", "abi/v1/commands.proto")

// Where the fingerprint of the schema these sources came from is kept.
//
// The generated protobuf classes live in src/main/generated and are committed,
// so no consumer needs buf to build — and so nothing regenerates them when the
// schema moves. That silence is the failure mode, not a convenience: adding a
// field to Dispatch and running `build` produced "cannot find symbol" on a
// getter, pointing at the call site rather than at the stale file that caused
// it. The fingerprint turns that into a sentence naming the task to run.
val schemaStamp = layout.projectDirectory.file("src/main/generated/abi-schema.sha256")

fun fingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (name in abiSources) {
        digest.update(name.toByteArray())
        digest.update(schemaDirectory.resolve(name).readBytes())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun schemaPresent(): Boolean =
    abiSources.all { schemaDirectory.resolve(it).exists() }

tasks.register<Exec>("generateProto") {
    description = "Regenerates the ABI sources from the gocraft-abi schema. Needs buf."
    group = "build"
    inputs.files(abiSources.map { schemaDirectory.resolve(it) })
    outputs.file(schemaStamp)
    doFirst {
        if (!schemaPresent()) {
            throw GradleException(
                "no ABI schema at ${schemaDirectory.absolutePath}. Point abiSchema in " +
                    "gradle.properties at a gocraft-abi checkout, or pass " +
                    "-PabiSchema=<path>."
            )
        }
    }
    workingDir = schemaDirectory
    // commands.proto is here because the envelope imports it: Invoke carries a
    // CommandArgumentType, so generating one without the other leaves the
    // envelope referring to a class that does not exist.
    commandLine("buf", "generate", "--template", file("buf.gen.yaml").absolutePath,
        "-o", projectDir.absolutePath,
        *abiSources.flatMap { listOf("--path", it) }.toTypedArray())
    doLast {
        schemaStamp.asFile.writeText(fingerprint() + "\n")
    }
}

// checkProto fails when the committed sources no longer match the schema.
//
// It needs no buf and no network: it hashes the two .proto files and compares
// against what generateProto recorded, so it is the same answer on a fresh
// clone as on the machine that generated them — which a timestamp would not be.
//
// Skipped, not failed, when the sibling checkout is absent. Someone building
// this module alone has no schema to have drifted from, and a check that
// refused to run without one would make the committed sources useless for
// exactly the case they exist for.
val checkProto = tasks.register("checkProto") {
    description = "Fails if the committed ABI sources drifted from the schema."
    group = "verification"
    doLast {
        if (!schemaPresent()) {
            logger.lifecycle("checkProto: no schema at ${schemaDirectory.absolutePath}, skipped")
            return@doLast
        }
        val recorded = if (schemaStamp.asFile.exists()) {
            schemaStamp.asFile.readText().trim()
        } else {
            ""
        }
        if (recorded != fingerprint()) {
            throw GradleException(
                "the generated ABI sources in src/main/generated were built from a " +
                    "different abi/v1 schema than the one at " +
                    "${schemaDirectory.absolutePath}. Run " +
                    "`./gradlew :gocraft-runtime-jvm:generateProto` and commit the result."
            )
        }
    }
}

tasks.named("check") { dependsOn(checkProto) }

tasks.jar {
    // The name the GoCraft host embeds and spawns.
    archiveFileName = "gocraft-runtime.jar"
    manifest {
        attributes(
            "Main-Class" to "fr.gocraft.runtime.Main",
            "Implementation-Version" to project.version,
        )
    }
    // The API jar and protobuf-javalite are the whole classpath, and the host
    // spawns this jar with `java -jar`, which ignores the classpath. Packing
    // them in is what makes that work.
    // The closure hides where those files come from, and one of them is now
    // built here rather than downloaded, so the dependency has to be spelled.
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    // The same flag runtime/jvm passes when the server spawns this runtime.
    // protobuf reaches for sun.misc.Unsafe, which Java 24 made a terminal
    // deprecation, and the four warning lines it prints are not something
    // anyone here can act on.
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
}

publishing.publications.named<MavenPublication>("maven") {
    pom {
        name = "gocraft-runtime-jvm"
        description = "The host the GoCraft server spawns to run JVM plugins. " +
            "A plugin does not depend on this."
    }
}
