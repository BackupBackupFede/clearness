package net.emeraude.clearness.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.emeraude.clearness.Clearness;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mod's persistent state: whether the Clearness is active, the cathedral /
 * chest bookkeeping, and the per-dimension "frontier" of blocks still to
 * spread from.
 *
 * API note: 26.x replaced the old writeNbt/readNbt SavedData with a
 * Codec-driven {@link SavedDataType}, so the whole state is described by
 * {@link #CODEC} instead of manual NBT calls.
 */
public class ClearnessState extends SavedData {

    private boolean active;
    private long startDay;
    private boolean monumentPlaced;
    private boolean captured;
    private long monumentPos;

    // Positions already infected and not yet "processed", PER DIMENSION: an
    // overworld position must not end up converted at the same coordinates in
    // the Nether. LinkedHashSet keeps a stable order and avoids duplicates.
    private final Map<String, Set<Long>> frontiers = new HashMap<>();

    public ClearnessState() {
    }

    private ClearnessState(boolean active, long startDay, boolean monumentPlaced,
                           boolean captured, long monumentPos, Map<String, List<Long>> frontiers) {
        this.active = active;
        this.startDay = startDay;
        this.monumentPlaced = monumentPlaced;
        this.captured = captured;
        this.monumentPos = monumentPos;
        frontiers.forEach((dim, positions) -> this.frontiers.put(dim, new LinkedHashSet<>(positions)));
    }

    public static final Codec<ClearnessState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("active", false).forGetter(s -> s.active),
            Codec.LONG.optionalFieldOf("startDay", 0L).forGetter(s -> s.startDay),
            Codec.BOOL.optionalFieldOf("monumentPlaced", false).forGetter(s -> s.monumentPlaced),
            Codec.BOOL.optionalFieldOf("captured", false).forGetter(s -> s.captured),
            Codec.LONG.optionalFieldOf("monumentPos", 0L).forGetter(s -> s.monumentPos),
            Codec.unboundedMap(Codec.STRING, Codec.LONG.listOf())
                    .optionalFieldOf("frontiers", Map.of()).forGetter(ClearnessState::frontiersAsLists)
    ).apply(instance, ClearnessState::new));

    public static final SavedDataType<ClearnessState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Clearness.MOD_ID, "clearness"),
            ClearnessState::new,
            CODEC,
            DataFixTypes.LEVEL);

    private Map<String, List<Long>> frontiersAsLists() {
        Map<String, List<Long>> out = new HashMap<>();
        frontiers.forEach((dim, positions) -> out.put(dim, new ArrayList<>(positions)));
        return out;
    }

    /** One global state stored on the overworld, shared by all dimensions. */
    public static ClearnessState get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    private Set<Long> frontierOf(ServerLevel level) {
        return frontiers.computeIfAbsent(
                level.dimension().identifier().toString(), k -> new LinkedHashSet<>());
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        setDirty();
    }

    public long getStartDay() {
        return startDay;
    }

    public void setStartDay(long startDay) {
        this.startDay = startDay;
        setDirty();
    }

    public boolean isMonumentPlaced() {
        return monumentPlaced;
    }

    public boolean isCaptured() {
        return captured;
    }

    public BlockPos getMonumentPos() {
        return BlockPos.of(monumentPos);
    }

    public void setMonumentPlaced(BlockPos chestPos) {
        this.monumentPlaced = true;
        this.monumentPos = chestPos.asLong();
        setDirty();
    }

    public void setCaptured() {
        this.captured = true;
        setDirty();
    }

    public void addToFrontier(ServerLevel level, BlockPos pos) {
        if (frontierOf(level).add(pos.asLong())) {
            setDirty();
        }
    }

    public void removeFromFrontier(ServerLevel level, long packedPos) {
        if (frontierOf(level).remove(packedPos)) {
            setDirty();
        }
    }

    /** Defensive copy: avoids ConcurrentModificationException while we iterate
     *  and modify the real frontier in parallel. */
    public List<Long> snapshotFrontier(ServerLevel level) {
        return new ArrayList<>(frontierOf(level));
    }

    public int frontierSize(ServerLevel level) {
        return frontierOf(level).size();
    }
}
