# gocraft-jvm

The JVM side of the [GoCraft](https://github.com/GoCraft-MC/GoCraft) plugin
system. It produces `gocraft-runtime.jar`, the process the server spawns to host
Java plugins.

It is not a Minecraft server, and it is not GoCraft ported to Java. GoCraft is
written in Go; this is one of the language backends its plugin system can drive,
alongside a future Lua and Python one.

## What it is today

A vertical slice: the runtime starts, speaks the ABI, and answers everything the
host sends it. It loads no plugins yet — classloading, the plugin API and the
dispatch path are the next milestone.

That is further than it sounds. Booting GoCraft against this jar produces:

```
plugins: startup aborted
  load plugin fr.oreo.hello: this runtime build loads no plugins yet:
  it speaks the ABI, but classloading and the plugin API are the next milestone
```

The message comes from this repository and reaches a Go console verbatim, which
means the socket, the handshake, the framing, the LOAD and the FAIL all crossed
the boundary intact — and that two independently generated protobuf
implementations agree on the wire.

| Implemented | Not yet |
| --- | --- |
| HELLO / WELCOME, with ABI refused rather than negotiated | Classloaders, isolated and shared |
| Length-delimited framing, dedicated writer thread | Plugin instantiation and `enable()` |
| LOAD answered with a reason, per plugin | Event dispatch on virtual threads |
| PING / PONG on the reader thread | Command invocation — the ABI has no frame for it |
| UNLOAD, READY, SHUTDOWN | Host proxy, services, scoped values |

## Building

Java 25 and Gradle. Nothing else: the ABI sources are committed, so buf and a
GoCraft checkout are needed only to regenerate them.

```
./gradlew build
```

The jar lands in `build/libs/gocraft-runtime.jar`, around 1.1 MB.

## Running it against GoCraft

The runtime is spawned by the server and never started by hand — the host opens
the socket first, so there is no window in which this could race a listener that
does not exist. Point a GoCraft test server at a local build:

```yaml
plugins:
  runtimes:
    jvm:
      jar_path: ../gocraft-jvm/build/libs/gocraft-runtime.jar
```

## The ABI

The schema is not copied here. It lives once, in GoCraft at `abi/v1/*.proto`,
and both sides generate from that one file — a schema transcribed anywhere else
is a second definition, free to drift without anything noticing.

```
./gradlew generateProto     # needs buf and a sibling GoCraft checkout
```

Two versions have to move together and are pinned in two places that must agree:
the plugin in `buf.gen.yaml` and `protobuf-javalite` in `build.gradle.kts`.
Gencode from a newer protobuf calls methods an older runtime does not have, and
it surfaces as dozens of `cannot find symbol` errors in files nobody edited.

`lite` is deliberate. Full protobuf-java is about 1.7 MB against a jar that has
to stay near 3 MB; the lite runtime gives up reflection and the text format,
neither of which a runtime speaking a fixed set of envelopes ever uses.

## Java 25

The baseline is a requirement, not a preference. Scoped values are final in 25
(JEP 506) and replace the `setContextClassLoader` try/finally dance the dispatch
path would otherwise need, and `synchronized` stopped pinning virtual threads in
24.

## Plugin state is not durable

This process can be killed and restarted while the server keeps running — three
missed pings are enough. Anything a plugin keeps in a field is gone on respawn.
That belongs at the top of the plugin documentation, not in a footnote.