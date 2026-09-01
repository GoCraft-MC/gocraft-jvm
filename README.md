# gocraft-jvm

The JVM side of the [GoCraft](https://github.com/GoCraft-MC/GoCraft) plugin
system. It produces three artefacts, because three different machines run them:

| Artefact | Who runs it |
| --- | --- |
| `gocraft-api-jvm` | the plugin. The only one an author names, and it has no dependencies at all |
| `gocraft-runtime-jvm` | the server, which extracts and spawns `gocraft-runtime.jar` |
| `gocraft-apt` | javac, while a plugin is compiled, and never again |

Keeping them apart is what keeps a plugin's classpath free of protobuf, of the
loader, and of a code generator.

It is not a Minecraft server, and it is not GoCraft ported to Java. GoCraft is
written in Go; this is one of the language backends its plugin system can drive,
alongside a future Lua and Python one.

## What it is today

The runtime starts, speaks the ABI, and loads plugins into isolated
classloaders. What it cannot do yet is dispatch an event to one: nothing
subscribes, so every DISPATCH is answered without cancelling.

| Implemented | Not yet |
| --- | --- |
| HELLO / WELCOME, with ABI refused rather than negotiated | Event dispatch on virtual threads |
| Length-delimited framing, dedicated writer thread | Config, data store and scheduler injection |
| Child-first classloader, `fr.gocraft.api` shared | Command invocation — the ABI has no frame for it |
| Plugin construction, `enable()` and `disable()` | Host proxy, services, scoped values |
| Unload that releases the classloader and its files | Plugin-defined events |
| LOAD answered with a reason, per plugin | Annotated classes without an entry (§05) |
| PING / PONG on the reader thread | |

A plugin is a `payload/*.jar` inside the bundle, implementing `Plugin` with one
constructor. Only `Host` can be injected so far; a constructor asking for
anything else is refused by name rather than failing obscurely.

## Why this is more than it looks

Booting GoCraft against this jar produced, before any plugin could load:

```
plugins: startup aborted
  load plugin fr.oreo.hello: this runtime build loads no plugins yet
```

That message was written here and reached a Go console verbatim — so the socket,
the handshake, the framing, the LOAD and the FAIL all crossed the process
boundary intact, and two independently generated protobuf implementations agree
on the wire.

## The two mistakes §13 says cost days

Both have a test, because neither is visible by reading the code.

**A shared API that is not shared.** If `fr.gocraft.api` came from the plugin's
own loader, the `Plugin` the runtime casts to would be a different class from
the one the plugin implements. Both are named `fr.gocraft.api.Plugin`, both look
right in a debugger, and the cast fails naming the same type twice.
`PluginClassLoader` delegates that package to the parent, always, and
`givesThePluginTheSameApiClassTheRuntimeUses` asserts it.

**A reference retained past unload.** One forgotten handler, MethodHandle or
scheduler task keeps the classloader alive, and with it every class the plugin
defined — Bukkit's classic `/reload` leak, invisible until a server has been up
for a week. `LoadedPlugin` exists to hold everything that must be released, and
`releasesEveryClassloaderItLoads` cycles twenty-five loads while watching weak
references.

## Building

Java 25 and Gradle. Nothing else: the ABI sources are committed, so buf and a
GoCraft checkout are needed only to regenerate them.

```
./gradlew build
```

The runtime jar lands in `gocraft-runtime-jvm/build/libs/gocraft-runtime.jar`,
around 1.3 MB; the API jar in `gocraft-api-jvm/build/libs/`, around 65 KB.

## Running it against GoCraft

The runtime is spawned by the server and never started by hand — the host opens
the socket first, so there is no window in which this could race a listener that
does not exist. Point a GoCraft test server at a local build, with an absolute
path or one relative to the server's working directory:

```yaml
plugins:
  runtimes:
    jvm:
      jar_path: /path/to/gocraft-jvm/gocraft-runtime-jvm/build/libs/gocraft-runtime.jar
```

## The ABI

The schema is not copied here. It lives once, in GoCraft at `abi/v1/*.proto`,
and both sides generate from that one file — a schema transcribed anywhere else
is a second definition, free to drift without anything noticing.

```
./gradlew generateProto     # needs buf and a sibling GoCraft checkout
```

Two versions have to move together and are pinned in two places that must agree:
the plugin in `gocraft-runtime-jvm/buf.gen.yaml` and `protobuf-javalite` in
`gocraft-runtime-jvm/build.gradle.kts`.
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