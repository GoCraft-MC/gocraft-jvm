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

## Writing a plugin

Three things, and the build turns them into one `.gcpkg` file the server reads.

**1. `plugin.toml`**, at the root of your project:

```toml
id      = "fr.oreo.shop"
version = "1.0.0"
api     = 1
runtime = "jvm"
entry   = "fr.oreo.shop.ShopPlugin"   # your class, not a jar path

[subscribe]
events = ["player.join", "block.break"]
perms  = ["shop.use"]

[commands]
tree = "commands.pb"                  # only if you declare commands
```

`[subscribe] events` is not decoration: the host sends you nothing you did not
ask for. And `perms` is what lets an event answer `can("shop.use")` from a map
instead of a round trip while the tick waits.

**2. A class implementing `Plugin`**, with one constructor:

```java
public final class ShopPlugin implements Plugin {
    private final Host host;

    public ShopPlugin(Host host) {   // injected by type, from a closed list
        this.host = host;
    }

    @Override public void enable()  { host.log("open for business"); }
    @Override public void disable() { /* only what you opened yourself */ }
}
```

No `static main` and no `extends JavaPlugin`. The runtime hosts many plugins in
one JVM, so a `main` would mean ten of them each claiming to own the process;
and dependencies through the constructor mean your fields are `final` with no
half-initialised window — the source of one NPE in two in Bukkit plugins.

Nothing you keep in a field survives a respawn. The runtime is a separate
process the server can kill and restart while it keeps running, so in-memory
state is a cache, never a record.

**3. Commands, if you have any** — see below.

### Your build

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    compileOnly("com.github.GoCraft-MC.gocraft-jvm:gocraft-api-jvm:v0.1.0")
    annotationProcessor("com.github.GoCraft-MC.gocraft-jvm:gocraft-apt:v0.1.0")
    // Served by JitPack until Maven Central; see below for why.
}
```

`compileOnly` is right and not a shortcut: the runtime already carries the API,
and a plugin shipping its own copy would load classes the host does not
recognise as its own. `annotationProcessor` is only needed if you use the
annotations.

Then:

```sh
./gradlew build
go run github.com/GoCraft-MC/gocraft-cli@latest \
    build -commands build/classes/java/main/gocraft/commands.json \
    -o shop.gcpkg .
```

`gocraft-apt` wrote that JSON while javac compiled you. `gocraft-cli` turns it
into the `commands.pb` in the bundle — the same program, reading the same kind
of file, as for a Go plugin.

### Declaring commands

Three ways to say it, one tree underneath. They are not three systems: the
annotations compile into builder calls, and the inheritance shim accumulates
into the same builder. Pick per command, mix within a plugin.

**Annotations**, when the shape is static:

```java
@Cmd("shop") @Permission("shop.use")
public final class ShopCommands {

    @Sub("sell <price>")
    void sell(CommandSender sender, @Range(min = 0.01, max = 1000) double price) { }

    @Sub("admin reload") @Permission("shop.admin")
    void reload(CommandSender sender) { }

    @Sub("say <message>")
    void say(CommandSender sender, @Greedy String message) { }
}
```

The argument type comes from the method signature — `double` is a decimal,
`PlayerRef` a player, `BlockPos` a position, an `enum` its own constants. A path
naming an argument the method does not take, or a parameter the path never asks
for, is a compile error rather than a command that never runs.

`@Permission` on a method guards the **last** literal of its path, not the
first: on `@Sub("admin reload")` it guards `reload`, which is the more precise
of the two.

**The builder**, when the shape is decided at runtime:

```java
CommandSet commands = Command.tree(
    Command.literal("shop").permission("shop.use")
        .then(Command.literal("sell")
            .then(Command.arg("price", new ArgType.Decimal(0.01, 1000.0))
                .executes(context -> sell(context.sender(), context.decimal("price")))))
);
host.registerCommands(commands);
```

**Inheritance**, if you are migrating from a plugin shaped that way:

```java
public final class ShopCommands extends SubCommand {
    public ShopCommands() {
        super("shop");
        permission("shop.use");
        add(Command.literal("sell").executes(context -> { ... }));
    }
}
host.registerCommands(new ShopCommands().build());
```

Whichever you use, the executor ids are minted by `gocraft-cli` and never by
you: handlers bind to paths (`shop sell <price>`), so inserting a command above
another cannot silently renumber it.

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

## Where the artefacts come from

[JitPack](https://jitpack.io), until they can go to Maven Central: `fr.gocraft`
needs a proven claim to `gocraft.fr`, and JitPack needs nothing but a tag. It
builds from the tag on demand, so pushing one is the whole publication step —
see [Writing a plugin](#writing-a-plugin) for the coordinates.

They are JitPack's coordinates, not ours, and they change the day these move to
Central. The Gradle plugin (deliverable 11) exists partly so that a plugin
author never writes them by hand.

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