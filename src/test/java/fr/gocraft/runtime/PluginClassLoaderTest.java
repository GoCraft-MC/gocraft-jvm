package fr.gocraft.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The delegation rule, checked directly.
///
/// It is one boolean, and everything downstream depends on it being right: too
/// generous and plugins cannot carry their own library versions, too narrow and
/// the runtime and the plugin end up with different copies of the same API
/// class.
class PluginClassLoaderTest {

    @Test
    void sharesThePlatform() {
        // Not the plugin's to replace, and a duplicated java.lang.String would
        // not link at all.
        assertTrue(PluginClassLoader.isShared("java.lang.String"));
        assertTrue(PluginClassLoader.isShared("java.util.concurrent.ConcurrentHashMap"));
        assertTrue(PluginClassLoader.isShared("jdk.internal.misc.Unsafe"));
        assertTrue(PluginClassLoader.isShared("javax.tools.JavaCompiler"));
    }

    @Test
    void sharesTheApi() {
        // The contract. If this is ever false, a plugin implements one
        // fr.gocraft.api.Plugin and the runtime casts to another.
        assertTrue(PluginClassLoader.isShared("fr.gocraft.api.Plugin"));
        assertTrue(PluginClassLoader.isShared("fr.gocraft.api.Host"));
    }

    /// The runtime's own internals are not part of the contract. A plugin that
    /// reached into them would be coupled to a class that changes without
    /// notice, and sharing them would let it hold a reference the host cannot
    /// see.
    @Test
    void doesNotShareTheRuntimeItself() {
        assertFalse(PluginClassLoader.isShared("fr.gocraft.runtime.PluginRegistry"));
        assertFalse(PluginClassLoader.isShared("fr.gocraft.runtime.Connection"));
    }

    /// The whole reason for child-first: two plugins each carrying their own
    /// version of the same library, neither seeing the other's.
    @Test
    void doesNotShareLibrariesOrPluginCode() {
        assertFalse(PluginClassLoader.isShared("com.google.protobuf.Message"));
        assertFalse(PluginClassLoader.isShared("com.zaxxer.hikari.HikariDataSource"));
        assertFalse(PluginClassLoader.isShared("fr.oreo.shop.ShopPlugin"));
    }

    /// A package that merely starts with the same letters is not the API.
    /// `fr.gocraft.apixyz` is somebody else's, and matching on a prefix without
    /// the dot would hand it to the parent.
    @Test
    void doesNotShareALookalikePackage() {
        assertFalse(PluginClassLoader.isShared("fr.gocraft.apiary.Bee"));
        assertFalse(PluginClassLoader.isShared("javaxml.Parser"));
        assertFalse(PluginClassLoader.isShared("javafoo.Bar"));
    }
}