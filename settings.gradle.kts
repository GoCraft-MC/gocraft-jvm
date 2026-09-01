// Gradle fetches the JDK it needs rather than requiring one to be installed.
//
// The baseline is Java 25, and a build machine that has it is the exception —
// JitPack, CI images and most contributors' laptops ship something older. This
// resolver lets the toolchain be downloaded on demand, so `./gradlew build`
// works on any JDK new enough to run Gradle itself, and an installed 25 is
// still found first.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "gocraft-jvm"

// Three artefacts, because three different machines run them.
//
// gocraft-api-jvm is what a plugin compiles against, and the only one an author
// ever names. It has no dependencies at all — not even protobuf — which is the
// property worth protecting: a plugin cannot reach the transport because the
// transport is not on its classpath.
//
// gocraft-runtime-jvm is the host. The server extracts and spawns it; a plugin
// author never depends on it, and shipping it inside the API jar would have put
// the loader on every plugin's classpath.
//
// gocraft-apt runs at a different time from both: inside javac, while a plugin
// is compiled, and never again. Packing it with the runtime would put a code
// generator in the jar the server spawns — against a budget that has to stay
// near 3 MB.
//
// §15: "The one exception is the annotation processor, which necessarily runs
// inside javac."
include("gocraft-api-jvm")
include("gocraft-runtime-jvm")
include("gocraft-apt")
