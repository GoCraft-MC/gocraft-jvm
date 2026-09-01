rootProject.name = "gocraft-jvm"

// The annotation processor is its own artefact because it runs at a different
// time from everything else here: inside javac, while a plugin is compiled, and
// never again. Packing it with the runtime would put a code generator in the
// jar the server spawns — against a budget that has to stay near 3 MB — and put
// it on every plugin's runtime classpath, where it has no business being.
//
// §15: "The one exception is the annotation processor, which necessarily runs
// inside javac."
include("gocraft-apt")
