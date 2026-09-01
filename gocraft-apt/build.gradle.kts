plugins {
    java
}

val junitVersion: String by project

dependencies {
    // The annotations and the command tree it builds. A plugin declares against
    // the same types, which is what lets the processor emit builder calls the
    // author could have written by hand.
    //
    // The dependency runs this way and only this way: the API knows nothing
    // about the processor, so a plugin that never annotates anything never
    // compiles a line of it.
    implementation(project(":gocraft-api-jvm"))

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // The processor is exercised by compiling real sources in-process, so the
    // test needs the same access to javac that javac gives its own plugins.
    jvmArgs(
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    )
}
