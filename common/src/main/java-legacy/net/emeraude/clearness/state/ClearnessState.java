package net.emeraude.clearness.state;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The persistent state, 1.21.1 side: the frontier of the Clearness (blocks already glass whose
 * neighbours still need a look), per dimension, and the monument.
 *
 * <p>1.21.1 saves through {@code SavedData.Factory} + {@code save(CompoundTag, Provider)}; 26.x
 * replaced that with a codec-based {@code SavedDataType} (see the java-modern twin).
 *
 * <p>The file id stays {@code "infestation"}: worlds from every earlier 1.21.1 release keep their
 * state with no migration step, and the NBT keys are the ones those releases wrote.
 */
public class ClearnessState extends SavedData {

    private static final String ID = "infestation";

    private boolean active = false;
    private long startDay = 0;
    // Fields of the 1.0.0 cursed chest: read and written back untouched so old saves round-trip.
    private boolean chestPlaced = false;
    private boolean chestOpened = false;
    private long chestPos = 0L;
    private boolean monumentPlaced = false;
    private boolean captured = false;
    private long monumentPos = 0L;
    // Per dimension; LinkedHashSet keeps a stable order and no duplicates.
    private final Map<String, Set<Long>> frontiers = new HashMap<>();

    public ClearnessState() {
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putBoolean("active", active);
        nbt.putLong("startDay", startDay);
        nbt.putBoolean("chestPlaced", chestPlaced);
        nbt.putBoolean("chestOpened", chestOpened);
        nbt.putLong("chestPos", chestPos);
        nbt.putBoolean("monumentPlaced", monumentPlaced);
        nbt.putBoolean("captured", captured);
        nbt.putLong("monumentPos", monumentPos);
        CompoundTag frontiersNbt = new CompoundTag();
        for (Map.Entry<String, Set<Long>> entry : frontiers.entrySet()) {
            long[] packed = new long[entry.getValue().size()];
            int i = 0;
            for (long pos : entry.getValue()) {
                packed[i++] = pos;
            }
            frontiersNbt.putLongArray(entry.getKey(), packed);
        }
        nbt.put("frontiers", frontiersNbt);
        return nbt;
    }

    private static ClearnessState load(CompoundTag nbt, HolderLookup.Provider registries) {
        ClearnessState state = new ClearnessState();
        state.active = nbt.getBoolean("active");
        state.startDay = nbt.getLong("startDay");
        state.chestPlaced = nbt.getBoolean("chestPlaced");
        state.chestOpened = nbt.getBoolean("chestOpened");
        state.chestPos = nbt.getLong("chestPos");
        state.monumentPlaced = nbt.getBoolean("monumentPlaced");
        state.captured = nbt.getBoolean("captured");
        state.monumentPos = nbt.getLong("monumentPos");
        CompoundTag frontiersNbt = nbt.getCompound("frontiers");
        for (String dimension : frontiersNbt.getAllKeys()) {
            Set<Long> frontier = state.frontiers.computeIfAbsent(dimension, k -> new LinkedHashSet<>());
            for (long pos : frontiersNbt.getLongArray(dimension)) {
                frontier.add(pos);
            }
        }
        // 1.0.0 saves kept a single, untyped frontier: it belongs to the overworld.
        long[] legacy = nbt.getLongArray("frontier");
        if (legacy.length > 0) {
            Set<Long> overworld = state.frontiers.computeIfAbsent("minecraft:overworld", k -> new LinkedHashSet<>());
            for (long pos : legacy) {
                overworld.add(pos);
            }
        }
        return state;
    }

    // The three-argument constructor: the two-argument one is a NeoForge addition, absent on Fabric.
    // The fixer type must NOT be null: vanilla 1.21.1 calls it unconditionally when reading the file,
    // so a null throws, the state is dropped, and every restart starts the Clearness from scratch.
    // (Fabric API and NeoForge both patch that out, which is why earlier releases never showed it.)
    // Command storage has no fixers: it reads our compound back untouched.
    private static final SavedData.Factory<ClearnessState> TYPE =
        new SavedData.Factory<>(ClearnessState::new, ClearnessState::load, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    /** One state for the whole server, stored with the overworld. */
    public static ClearnessState get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE, ID);
    }

    private Set<Long> frontierOf(ServerLevel level) {
        return frontiers.computeIfAbsent(level.dimension().location().toString(), k -> new LinkedHashSet<>());
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

    /** A copy: the wave iterates it while changing the real frontier. */
    public List<Long> snapshotFrontier(ServerLevel level) {
        return new ArrayList<>(frontierOf(level));
    }

    public int frontierSize(ServerLevel level) {
        return frontierOf(level).size();
    }
}
