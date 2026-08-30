plugins {
    java
}

group = "fr.gocraft"
version = "0.1.0"

// Java 25 is the baseline, not a preference. Scoped values are final in 25
// (JEP 506) and replace the setContextClassLoader try/finally dance the
// dispatch path would otherwise need, and synchronized stopped pinning virtual
// threads in 24 — both are load-bearing for what this runtime does.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Must equal the plugin version pinned in buf.gen.yaml. The generated sources
// are committed, so the runtime on the classpath and the compiler that emitted
// them are two halves of one decision: gencode from a newer protobuf calls
// methods an older runtime does not have, and it surfaces as "cannot find
// symbol" in files nobody wrote.
val protobufVersion = "4.36.0"

dependencies {
    // The lite runtime. It gives up reflection and the text format, which a
    // runtime speaking a fixed set of envelopes never uses, and costs about a
    // tenth of what full protobuf-java would against a 3 MB budget.
    implementation("com.google.protobuf:protobuf-javalite:$protobufVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
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
    commandLine("buf", "generate", "--template", file("buf.gen.yaml").absolutePath, "-o", projectDir.absolutePath)
}

// Byte-identical across machines and runs, for the same reason gocraft-cli
// stamps every .gcpkg entry with a fixed 1980 epoch.
//
// A jar is a zip, and a zip stores a modification time per entry, so the
// default output differs on every build even when nothing changed. That is not
// cosmetic here: the GoCraft host extracts this jar under a name derived from
// its content hash, precisely so a server can never pick up the jar a previous
// version left behind. Non-reproducible bytes would mean a fresh cache entry
// per build, and a release that cannot be verified against its source.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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
    // protobuf-javalite is the only dependency, and the host spawns this jar
    // with `java -jar`, which ignores the classpath. Packing it in is what
    // makes that work.
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
    // The same flag runtime/jvm passes when the server spawns this runtime.
    // protobuf reaches for sun.misc.Unsafe, which Java 24 made a terminal
    // deprecation, and the four warning lines it prints are not something
    // anyone here can act on.
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}