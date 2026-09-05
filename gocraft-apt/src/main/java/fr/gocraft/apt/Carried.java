package fr.gocraft.apt;

/// What one field holds, once the processor has worked it out.
///
/// The vocabulary is the manifest's, and closed for the reason the manifest
/// gives: a field's type used to be a free string nobody resolved, which was
/// safe while every field was a scalar — the flat list of names and types was
/// itself the whole shape. A field that is a record breaks that, so records are
/// declared and only these four shapes exist.
sealed interface Carried {

    /// How the manifest spells it, which is what a subscriber's own manifest is
    /// compared against.
    String manifest();

    /// The Java type, for the code the emitters write around it.
    String java();

    /// A primitive, a String or a byte[].
    record Scalar(EventProcessor.Kind kind, String java) implements Carried {
        @Override
        public String manifest() {
            return kind.manifest;
        }
    }

    /// The one vocabulary type an event may carry.
    ///
    /// Both runtimes have a hand-written PlayerRef and both bind it to the
    /// dispatch as they decode it, so an event carrying one hands its subscriber
    /// somebody they can answer rather than an id they must turn into a handle.
    record Player() implements Carried {
        @Override
        public String manifest() {
            return "PlayerRef";
        }

        @Override
        public String java() {
            return "fr.gocraft.api.PlayerRef";
        }
    }

    /// A class the author marked @EventValue.
    ///
    /// name is what the manifest calls it and java is the class the codec reads
    /// and writes. They are the same string unless the annotation renamed it,
    /// which is how two plugins can spell one record differently in their own
    /// source and still describe the same shape.
    record Compound(String name, String java, String codec) implements Carried {
        @Override
        public String manifest() {
            return name;
        }
    }

    /// A List of any of the above. One level: a list of lists has no author
    /// asking for it, and the manifest refuses one for the same reason.
    record Listed(Carried element, String java) implements Carried {
        @Override
        public String manifest() {
            return "[]" + element.manifest();
        }
    }
}