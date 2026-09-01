package fr.gocraft.gradle;

import org.gradle.api.provider.Property;

/// What a plugin author may say about their build, and no more.
///
/// Everything absent here is absent on purpose. The API version, the processor
/// version and the tool that packs the bundle are not settings: they are this
/// plugin's own version, so a build cannot pair an API with a packer that
/// disagrees about the bundle format. An author who needs to move forward moves
/// this plugin's version, once, and all three follow.
public abstract class GoCraftExtension {

    /// The bundle's file name, without its extension. Defaults to the project
    /// name.
    public abstract Property<String> getBundleName();

    /// Which release of gocraft-cli packs the bundle.
    ///
    /// Defaults to this plugin's version. Set it only to test a tool that has
    /// not shipped with a matching plugin yet — a build that pins them apart is
    /// a build asserting the two formats still agree.
    public abstract Property<String> getToolVersion();

    /// Where the release binaries are fetched from.
    ///
    /// Present for a mirror or an air-gapped network, not for pointing the
    /// build at something else. The asset naming under it is a contract:
    /// `gocraft-cli_<tag>_<os>_<arch>`, with `checksums.txt` beside it.
    public abstract Property<String> getToolRepository();

    /// A packer already on this machine, instead of the published one.
    ///
    /// For a build with no network, and for testing a gocraft-cli that has not
    /// shipped yet. Nothing is downloaded and nothing is verified when this is
    /// set — you are asserting you know what that file is.
    public abstract Property<String> getToolPath();

    /// Whether the bundle carries the plugin's own dependencies.
    ///
    /// On by default, because a plugin runs in an isolated classloader with
    /// only `fr.gocraft.api` shared: a library it does not ship is a library it
    /// does not have. Turn it off when every dependency is provided some other
    /// way and you would rather catch a mistake than hide it behind a fat
    /// bundle.
    public abstract Property<Boolean> getIncludeDependencies();
}
