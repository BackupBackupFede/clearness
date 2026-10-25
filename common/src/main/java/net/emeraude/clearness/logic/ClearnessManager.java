package net.emeraude.clearness.logic;

import net.emeraude.clearness.compat.Compat;
import net.emeraude.clearness.ClearnessConfig;
import net.emeraude.clearness.state.ClearnessState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Advances the Clearness "frontier" on each tick wave.
 *
 * The principle: we NEVER scan the whole world. We keep a list of already
 * infected blocks that still have at least one non-infected, non-protected
 * neighbour, and only look at their 6 direct neighbours. The cost is therefore
 * proportional to the frontier size, not the world size.
 */
public final class ClearnessManager {

    private static final Random RANDOM = new Random();
    private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();

    // Blocks we never convert, even when everything else must go: "technical"
    // blocks placed by an admin/datapack, not natural structures (those are
    // handled by StructureProtector).
    private static final Set<Block> NEVER_CONVERT = Set.of(
            Blocks.AIR,
            Blocks.CAVE_AIR,
            Blocks.VOID_AIR,
            Blocks.STRUCTURE_BLOCK,
            Blocks.STRUCTURE_VOID,
            Blocks.JIGSAW,
            Blocks.BARRIER,
            Blocks.COMMAND_BLOCK,
            Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK,
            Blocks.LIGHT,
            Blocks.GLASS // already "infected" (or just plain glass); don't loop on it
    );

    private final StructureProtector structureProtector = new StructureProtector();
    private final CraftedBlockGuard craftedGuard = new CraftedBlockGuard();

    /** Protection decision from the position alone (re-reads the block). */
    private boolean isBlockedAt(ServerLevel level, BlockPos pos) {
        return isBlockedAt(level, pos, level.getBlockState(pos));
    }

    /**
     * Core of the protection logic, priority by priority:
     *  1. Crafted (crafted) block -> always kept, it's the structure.
     *  2. Natural terrain -> always a TARGET, even against a structure, UNLESS
     *     it forms the small trail directly beneath a crafted block.
     *  3. Other blocks -> kept only if inside a generated structure's volume
     *     or bordering a crafted block.
     */
    private boolean isBlockedAt(ServerLevel level, BlockPos pos, BlockState state) {
        if (CraftedBlockGuard.isCrafted(state)) {
            return true;
        }
        if (CraftedBlockGuard.isNaturalTarget(state)) {
            return isSupportTrail(level, pos);
        }
        return structureProtector.isProtected(level, pos) || craftedGuard.isProtected(level, pos);
    }

    /**
     * @return true if a crafted block sits within STRUCTURE_TRAIL_BELOW blocks
     *         directly above: the position is then the natural base of a
     *         structure and is spared.
     */
    private boolean isSupportTrail(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int i = 0; i < ClearnessConfig.STRUCTURE_TRAIL_BELOW; i++) {
            cursor.move(Direction.UP);
            if (CraftedBlockGuard.isCrafted(level.getBlockState(cursor))) {
                return true;
            }
        }
        return false;
    }

    /** Explains the protection decision, for the /clearness check command. */
    public String describeProtection(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (CraftedBlockGuard.isCrafted(state)) {
            return "PROTECTED (crafted block)";
        }
        if (CraftedBlockGuard.isNaturalTarget(state)) {
            return isSupportTrail(level, pos)
                    ? "PROTECTED (ground trail below a structure)"
                    : "TARGET (natural terrain)";
        }
        if (structureProtector.isProtected(level, pos)) {
            return "PROTECTED (structure volume)";
        }
        if (craftedGuard.isProtected(level, pos)) {
            return "PROTECTED (near a crafted block)";
        }
        return "TARGET";
    }

    /** Called every server tick by the loader-specific event hook. */
    public void onServerTick(MinecraftServer server) {
        boolean waveTick = server.getTickCount() % ClearnessConfig.TICKS_BETWEEN_WAVES == 0;
        boolean pursuitTick = server.getTickCount() % ClearnessConfig.PURSUIT_INTERVAL_TICKS == 0;
        if (!waveTick && !pursuitTick) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            ClearnessState state = ClearnessState.get(level);
            if (!state.isActive()) {
                continue;
            }
            if (waveTick) {
                tickWave(level, state);
            }
            if (pursuitTick) {
                pursuePlayers(level, state);
            }
        }
    }

    /**
     * Makes the Clearness "hunt" players: seeds new outbreaks near each player
     * so the glass wave follows them wherever they go, instead of staying
     * confined around the starting point.
     */
    private void pursuePlayers(ServerLevel level, ClearnessState state) {
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            int seeded = 0;
            for (int attempt = 0; attempt < 8 * ClearnessConfig.PURSUIT_CLUSTERS
                    && seeded < ClearnessConfig.PURSUIT_CLUSTERS; attempt++) {
                if (trySeedNear(level, state, player.blockPosition(),
                        ClearnessConfig.PURSUIT_MIN_RADIUS, ClearnessConfig.PURSUIT_MAX_RADIUS)) {
                    seeded++;
                }
            }
        }
    }

    /**
     * Tries to convert a cluster at a random distance from the given point.
     * We start near the player's altitude then descend until we find a
     * convertible block: this works on the surface (we hit the ground) and in
     * a cave (we hit the rock around them).
     */
    private boolean trySeedNear(ServerLevel level, ClearnessState state, BlockPos center,
                                int minRadius, int maxRadius) {
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        int dist = minRadius + RANDOM.nextInt(maxRadius - minRadius + 1);
        int dx = (int) Math.round(Math.cos(angle) * dist);
        int dz = (int) Math.round(Math.sin(angle) * dist);
        int dy = RANDOM.nextInt(17) - 8; // up to +/-8 blocks around the player's altitude
        BlockPos start = center.offset(dx, dy, dz);

        if (!level.isLoaded(start) || isBlockedAt(level, start)) {
            return false;
        }

        BlockPos.MutableBlockPos cursor = start.mutable();
        for (int i = 0; i < 24 && cursor.getY() > Compat.minY(level); i++) {
            BlockState blockState = level.getBlockState(cursor);
            if (isConvertible(blockState)) {
                // Re-check protection at the REAL conversion position: the
                // descent may have entered a structure's Y range or come close
                // to an crafted block.
                if (isBlockedAt(level, cursor, blockState)) {
                    return false;
                }
                return convertCluster(level, state, cursor.immutable()) > 0;
            }
            cursor.move(Direction.DOWN);
        }
        return false;
    }

    /**
     * Converts a CLUSTER of blocks (organic sphere of radius
     * SEED_CLUSTER_RADIUS) around the center, instead of a single 1x1 block,
     * so every new outbreak is immediately visible.
     *
     * @return the number of blocks actually converted.
     */
    private int convertCluster(ServerLevel level, ClearnessState state, BlockPos center) {
        int r = ClearnessConfig.SEED_CLUSTER_RADIUS;
        int converted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r * r + 1) {
                        continue; // sphere, not cube
                    }
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState blockState = level.getBlockState(cursor);
                    if (!isConvertible(blockState) || isBlockedAt(level, cursor, blockState)) {
                        continue;
                    }
                    level.setBlock(cursor, GLASS, Block.UPDATE_ALL);
                    state.addToFrontier(level, cursor.immutable());
                    converted++;
                }
            }
        }
        return converted;
    }

    private void tickWave(ServerLevel level, ClearnessState state) {
        List<Long> snapshot = state.snapshotFrontier(level);
        if (snapshot.isEmpty()) {
            return; // nothing to do this wave (or the Clearness finished)
        }

        double multiplier = getSpeedMultiplier(level, state);
        int budget = (int) Math.min(ClearnessConfig.MAX_CONVERSIONS_PER_WAVE * multiplier,
                ClearnessConfig.MAX_CONVERSIONS_HARD_CAP);
        double spreadChance = Math.min(1.0, ClearnessConfig.SPREAD_CHANCE * multiplier);
        List<Long> toRemove = new ArrayList<>();
        List<BlockPos> toAdd = new ArrayList<>();

        for (Long packed : snapshot) {
            if (budget <= 0) {
                break;
            }

            BlockPos pos = BlockPos.of(packed);
            boolean stillExpandable = false;

            for (Direction dir : Direction.values()) {
                if (budget <= 0) {
                    stillExpandable = true; // work remains, we'll redo it later
                    break;
                }

                BlockPos neighbor = pos.relative(dir);

                if (!level.isLoaded(neighbor)) {
                    // Chunk not loaded: retry later, we don't lose this front.
                    stillExpandable = true;
                    continue;
                }

                BlockState neighborState = level.getBlockState(neighbor);

                if (!ClearnessConfig.INFECT_BEDROCK && neighborState.is(Blocks.BEDROCK)) {
                    continue;
                }

                // Fast reject (air, glass already placed, fluids...) before the
                // more expensive protection test.
                if (!isConvertible(neighborState)) {
                    continue;
                }

                if (isBlockedAt(level, neighbor, neighborState)) {
                    continue; // permanently blocked on this side
                }

                if (RANDOM.nextDouble() < spreadChance) {
                    level.setBlock(neighbor, GLASS, Block.UPDATE_ALL);
                    toAdd.add(neighbor);
                    budget--;
                } else {
                    stillExpandable = true; // not converted this time, we'll retry
                }
            }

            if (!stillExpandable) {
                toRemove.add(packed);
            }
        }

        for (BlockPos pos : toAdd) {
            state.addToFrontier(level, pos);
        }
        for (Long packed : toRemove) {
            state.removeFromFrontier(level, packed);
        }
    }

    private boolean isConvertible(BlockState blockState) {
        if (NEVER_CONVERT.contains(blockState.getBlock())) {
            return false;
        }
        if (CraftedBlockGuard.isCrafted(blockState)) {
            return false; // never the crafted block itself
        }
        return blockState.getFluidState().isEmpty(); // leave fluids alone
    }

    /**
     * Starts the Clearness by converting a patch of clusters around a position
     * ("patient zero"), and activates propagation.
     */
    public void seedClearness(ServerLevel level, BlockPos origin) {
        ClearnessState state = ClearnessState.get(level);

        int converted = 0;
        int radiusBlocks = ClearnessConfig.SEED_RADIUS_CHUNKS * 16;

        // One glass cluster per column (every 4 blocks): the starting point is
        // a real, clearly visible sheet of glass, not scattered 1x1 blocks.
        for (int dx = -radiusBlocks; dx <= radiusBlocks; dx += 4) {
            for (int dz = -radiusBlocks; dz <= radiusBlocks; dz += 4) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                BlockPos columnPos = new BlockPos(x, origin.getY(), z);
                if (!level.isLoaded(columnPos)) {
                    continue;
                }
                int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                converted += convertCluster(level, state, new BlockPos(x, topY, z));
            }
        }

        // The origin point itself.
        converted += convertCluster(level, state, origin);

        // Safety net: guarantee at least one starting point.
        if (converted == 0) {
            level.setBlock(origin, GLASS, Block.UPDATE_ALL);
            state.addToFrontier(level, origin);
        }

        state.setStartDay(getCurrentDay(level));
        state.setActive(true);
    }

    /** Current in-game day, based on the overworld clock so it's consistent
     *  across all dimensions. */
    public static long getCurrentDay(ServerLevel level) {
        return Compat.dayClock(level) / 24000L;
    }

    private double getSpeedMultiplier(ServerLevel level, ClearnessState state) {
        long daysElapsed = Math.max(0, getCurrentDay(level) - state.getStartDay());
        return Math.min(ClearnessConfig.MAX_SPEED_MULTIPLIER,
                1.0 + ClearnessConfig.DAILY_ACCELERATION * daysElapsed);
    }

    public double getSpeedMultiplier(ServerLevel level) {
        return getSpeedMultiplier(level, ClearnessState.get(level));
    }

    public long getDaysElapsed(ServerLevel level) {
        return Math.max(0, getCurrentDay(level) - ClearnessState.get(level).getStartDay());
    }

    /**
     * Triggers a localized outbreak: seeds up to {@code clusters} glass
     * clusters at a random distance from the center. Used by interaction
     * blocks (mini outbreak) and the bed (big outbreak). Does nothing if the
     * Clearness is not active.
     *
     * @return the number of clusters actually seeded.
     */
    public int outbreak(ServerLevel level, BlockPos center, int clusters, int minRadius, int maxRadius) {
        ClearnessState state = ClearnessState.get(level);
        if (!state.isActive()) {
            return 0;
        }
        int seeded = 0;
        int attempts = clusters * 4; // margin: some attempts land in air/protected blocks
        for (int i = 0; i < attempts && seeded < clusters; i++) {
            if (trySeedNear(level, state, center, minRadius, maxRadius)) {
                seeded++;
            }
        }
        return seeded;
    }

    public void stopClearness(ServerLevel level) {
        ClearnessState.get(level).setActive(false);
    }

    public boolean isRunning(ServerLevel level) {
        return ClearnessState.get(level).isActive();
    }

    public int getFrontierSize(ServerLevel level) {
        return ClearnessState.get(level).frontierSize(level);
    }

    /** Effective protection at a position, for the /clearness check command. */
    public boolean isProtectedAt(ServerLevel level, BlockPos pos) {
        return isBlockedAt(level, pos);
    }
}
