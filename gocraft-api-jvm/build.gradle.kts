// What a plugin compiles against, and the only artefact an author names.
//
// It has no dependencies. That is a property to defend rather than an accident:
// protobuf, the envelope classes and the loader all live on the other side of
// the boundary, so a plugin cannot reach the transport by importing it, and the
// jar an author puts on their classpath stays a few tens of kilobytes.

plugins {
    `java-library`
}

val junitVersion: String by project

dependencies {
    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The event classes protoc-gen-gocraft emits from abi/v1/events.proto. They are
// committed, so building this needs neither buf nor a GoCraft checkout, and
// they are a source root rather than a build output for the same reason.
sourceSets {
    main {
        java {
            srcDir("src/main/generated")
        }
    }
}
