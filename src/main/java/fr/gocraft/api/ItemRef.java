package fr.gocraft.api;

/// An item, as a command argument carries one.
///
/// Deliberately not a whole stack. A command's item argument is parsed from an
/// id and a count, so enchantments, firework data and pot decorations would
/// arrive empty on every invocation and mean nothing when they did not. A
/// vocabulary type is a wire contract: appending a field later is compatible,
/// explaining one nobody fills is not.
public record ItemRef(String id, long count, long damage) {

    public static final ItemRef NONE = new ItemRef("minecraft:air", 0, 0);

    /// Reads one out of a payload. A malformed value reads as [#NONE] rather
    /// than throwing, like the rest of the vocabulary.
    public static ItemRef of(Value value) {
        if (!(value instanceof Value.List list) || list.size() < 1) {
            return NONE;
        }
        String id = list.at(0) instanceof Value.Text(String name) ? name : NONE.id();
        return new ItemRef(id, number(list.at(1)), number(list.at(2)));
    }

    private static long number(Value value) {
        return value instanceof Value.Int(long number) ? number : 0L;
    }

    public boolean present() {
        return count > 0;
    }

    @Override
    public String toString() {
        return count + " " + id;
    }
}