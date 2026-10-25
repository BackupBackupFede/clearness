package net.emeraude.clearness.world;

import net.emeraude.clearness.compat.Compat;
import net.emeraude.clearness.Clearness;
import net.emeraude.clearness.ClearnessConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

/**
 * Once the Clearness is released, every use of civilization's comforts comes
 * at a price. The loader modules call into these hooks; the decisions live
 * here so both loaders behave identically.
 *
 * Until the chest is opened, these are inert (outbreak() does nothing while
 * the Clearness is inactive).
 */
public final class OutbreakTriggers {

    private OutbreakTriggers() {}

    /** "Civilized" interaction blocks that attract the Clearness. */
    private static final Set<Block> INTERACTION_BLOCKS = Set.of(
            Blocks.CRAFTING_TABLE,
            Blocks.ENCHANTING_TABLE,
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
            Blocks.GRINDSTONE, Blocks.STONECUTTER, Blocks.SMITHING_TABLE,
            Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE,
            Blocks.BREWING_STAND, Blocks.LECTERN, Blocks.BELL);

    /** A player used a block: spawn a mini outbreak if it's a workstation. */
    public static void onUseBlock(ServerLevel level, BlockPos pos) {
        if (INTERACTION_BLOCKS.contains(level.getBlockState(pos).getBlock())) {
            Clearness.MANAGER.outbreak(level, pos,
                    ClearnessConfig.MINI_OUTBREAK_CLUSTERS,
                    ClearnessConfig.MINI_OUTBREAK_MIN_RADIUS,
                    ClearnessConfig.MINI_OUTBREAK_MAX_RADIUS);
        }
    }

    /**
     * A player stopped sleeping: if the night was actually skipped, the
     * Clearness feasts around the bed.
     *
     * NB: we can't rely on an "is day" helper here — ambient darkness is
     * recomputed later in the tick, so at wake time it still reads as night.
     * After a skip the clock has just been reset, so we test the time itself.
     */
    public static void onStopSleeping(ServerLevel level, BlockPos bedPos) {
        if (Compat.dayClock(level) % 24000L < 2000L) {
            Clearness.MANAGER.outbreak(level, bedPos,
                    ClearnessConfig.BED_OUTBREAK_CLUSTERS,
                    ClearnessConfig.BED_OUTBREAK_MIN_RADIUS,
                    ClearnessConfig.BED_OUTBREAK_MAX_RADIUS);
        }
    }
}
