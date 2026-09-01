// The build plugin: what turns a normal Java project into a GoCraft plugin.
//
// It exists so that an author writes none of the three things that would
// otherwise have to be kept in step by hand — the API coordinate, the processor
// coordinate, and the version of the tool that packs the bundle. All three come
// from this plugin's own version, so they cannot disagree.

plugins {
    `java-gradle-plugin`
    `maven-publish`
}

val junitVersion: String by project

dependencies {
    // A plugin that compiles proves nothing. The test runs a real build, in a
    // real directory, and looks at the bundle that comes out.
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("gocraft") {
            id = "fr.gocraft.plugin"
            implementationClass = "fr.gocraft.gradle.GoCraftPlugin"
            displayName = "GoCraft plugin builder"
            description = "Compiles a JVM plugin and packs it into a .gcpkg bundle."
        }
    }
}

publishing.publications.named<MavenPublication>("maven") {
    pom {
        name = "gocraft-gradle-plugin"
        description = "Builds GoCraft plugin bundles from a Gradle project."
    }
}

// The plugin has to know its own version: it is what the API and processor
// coordinates are built from, so a plugin author never writes one. Generated
// rather than hardcoded, because a constant would be a second place to bump and
// the one that gets forgotten.
// Its own directory: build/generated already belongs to the plugin descriptor
// generator, and two tasks writing one directory is a dependency Gradle refuses
// to guess at.
val versionResource by tasks.registering {
    val target = layout.buildDirectory.file("generated-resources/gocraft-plugin.properties")
    val declared = project.version.toString()
    inputs.property("version", declared)
    outputs.file(target)
    doLast {
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeText("version=$declared\n")
        }
    }
}

sourceSets.main {
    resources.srcDir(versionResource.map { it.outputs.files.singleFile.parentFile })
}
