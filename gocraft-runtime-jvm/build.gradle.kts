// The host: the jar the GoCraft server extracts and spawns to run JVM plugins.
//
// No plugin depends on this. It sits on the other side of the boundary from
// gocraft-api-jvm, which it implements — Host, the loader, the codecs — and
// speaks the ABI, which is why protobuf is here and not there.

plugins {
    java
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
// sibling GoCraft checkout because there is no shared schema repository yet;
// deliverable 01 gives the ABI its own, and this becomes a real dependency.
val abiSchema: String by project

tasks.register<Exec>("generateProto") {
    description = "Regenerates the ABI sources from the GoCraft schema. Needs buf."
    group = "build"
    val schema = file(abiSchema)
    doFirst {
        if (!schema.resolve("abi/v1/envelope.proto").exists()) {
            throw GradleException(
                "no ABI schema at ${schema.absolutePath}. Point abiSchema in " +
                    "gradle.properties at a GoCraft checkout, or pass " +
                    "-PabiSchema=<path>."
            )
        }
    }
    workingDir = schema
    // The envelope and the command schema. events.proto and options.proto are
    // read by protoc-gen-gocraft to decide what the generated event classes
    // look like; they are never serialised, so their protobuf message classes
    // would be dead weight in a jar with a 3 MB budget — and an invitation to
    // import fr.gocraft.abi.v1.BlockBreak instead of the event class that
    // carries the named accessors.
    //
    // commands.proto is here because the envelope imports it: Invoke carries a
    // CommandArgumentType, so generating one without the other leaves the
    // envelope referring to a class that does not exist.
    commandLine("buf", "generate", "--template", file("buf.gen.yaml").absolutePath,
        "-o", projectDir.absolutePath,
        "--path", "abi/v1/envelope.proto", "--path", "abi/v1/commands.proto")
}

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
