package fr.gocraft.api;

/// A block position.
///
/// Hand-written, like the rest of the vocabulary: §03 keeps roughly fifteen
/// stable types out of the generator because ergonomics matter here and
/// generated code would be mediocre. `distanceSq` is exactly the sort of thing
/// no generator would think to add.
public record BlockPos(long x, long y, long z) {

    /// Reads one out of an event payload. An absent or malformed value reads as
    /// the origin rather than throwing: a handler holding the tick should not
    /// be able to crash the dispatch by reading a field.
    public static BlockPos of(Value value) {
        if (!(value instanceof Value.List list) || list.size() < 3) {
            return new BlockPos(0, 0, 0);
        }
        return new BlockPos(number(list.at(0)), number(list.at(1)), number(list.at(2)));
    }

    private static long number(Value value) {
        return value instanceof Value.Int(long number) ? number : 0L;
    }

    /// Squared distance, so a comparison against a radius needs no square root.
    public long distanceSq(BlockPos other) {
        long dx = x - other.x;
        long dy = y - other.y;
        long dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
