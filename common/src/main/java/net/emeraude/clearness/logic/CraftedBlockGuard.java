package net.emeraude.clearness.logic;

import net.emeraude.clearness.compat.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Second protection layer, complementary to {@link StructureProtector}:
 * "crafted" blocks — those that never appear in natural terrain but only in
 * structures (or player builds) — are never converted, and neither are the
 * blocks within {@link #RADIUS} around them.
 *
 * A tree's wood is natural; the cobblestone next to it is an crafted build.
 * This catches everything the structure bounding-box protection misses
 * (underwater ruined portals, chests, mineshaft corridors overflowing their
 * bbox, etc.).
 *
 * Performance: we do NOT scan a cube around every block to convert. We
 * pre-scan each 16x16x16 section once ("does this section contain at least one
 * crafted block?") and cache the result. The fast path (pristine terrain,
 * the vast majority of cases) costs at most 8 cache reads per block.
 *
 * Accepted limitation: the per-section cache lives for the server's lifetime.
 * An crafted block placed by a player AFTER the scan passed through a section
 * already cached as "empty" won't be detected until a restart.
 */
public final class CraftedBlockGuard {

    /** Radius (in blocks) of the area spared around an crafted block. */
    private static final int RADIUS = 3;

    /** Cross-loader convention tag for every ore (Fabric and NeoForge both
     *  populate "c:ores"), so common code needs neither loader's API. */
    private static final TagKey<Block> ORES = Compat.blockTag("c", "ores");

    /** Crafted blocks that don't have a convenient tag to group them. */
    private static final Set<Block> CRAFTED_BLOCKS = Set.of(
            // Portals (ruined or not)
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            // Worked stone (villages, strongholds, jungle temples...)
            Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
            Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
            Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
            Blocks.SMOOTH_STONE, Blocks.BRICKS,
            Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_GRANITE,
            // Pyramids / desert temples
            Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.SMOOTH_SANDSTONE,
            Blocks.CUT_RED_SANDSTONE, Blocks.CHISELED_RED_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE,
            // Treasure
            Blocks.GOLD_BLOCK,
            // Mineshafts
            Blocks.COBWEB, Blocks.LADDER,
            // Strongholds, igloos
            Blocks.IRON_BARS, Blocks.BOOKSHELF, Blocks.GLASS_PANE,
            // Structure lighting
            Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LANTERN, Blocks.SOUL_LANTERN,
            // Ocean monuments
            Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN,
            // Nether fortresses
            Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS,
            Blocks.CRACKED_NETHER_BRICKS, Blocks.CHISELED_NETHER_BRICKS,
            // Bastions
            Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
            Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.CHISELED_POLISHED_BLACKSTONE,
            Blocks.GILDED_BLACKSTONE,
            // Ancient cities
            Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS,
            Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES,
            Blocks.CHISELED_DEEPSLATE, Blocks.REINFORCED_DEEPSLATE,
            // End cities
            Blocks.END_STONE_BRICKS, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.END_ROD
    );

    /** Foliage / natural vegetation without a single convenient tag. */
    private static final Set<Block> NATURAL_EXTRA = Set.of(
            Blocks.GRAVEL, Blocks.CLAY, Blocks.END_STONE, Blocks.MOSS_BLOCK,
            Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.DEAD_BUSH, Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.ICE
    );

    // cache per dimension: section (SectionPos long) -> contains at least one
    // crafted block?
    private final Map<String, Map<Long, Boolean>> sectionCache = new HashMap<>();

    /**
     * Natural terrain blocks the infestation must target EVEN right next to a
     * structure: grass/dirt blocks, rock, ores, foliage. They never benefit
     * from the crafted protection radius (but remain protected if they are
     * inside a structure's actual volume — that's StructureProtector's job,
     * notably for the trail).
     */
    /**
     * Every ore. "c:ores" is filled by Fabric API or NeoForge when present; without Fabric API it
     * is empty on Fabric, so the vanilla ore tags it is built from are tested directly too. The
     * union is exactly what "c:ores" holds for vanilla blocks.
     */
    private static boolean isOre(BlockState state) {
        if (state.is(ORES) || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.ANCIENT_DEBRIS)) {
            return true;
        }
        for (TagKey<Block> tag : VANILLA_ORES) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    // By name: 26.x moved most of these out of BlockTags.
    private static final List<TagKey<Block>> VANILLA_ORES = List.of(
            Compat.blockTag("minecraft", "coal_ores"), Compat.blockTag("minecraft", "copper_ores"),
            Compat.blockTag("minecraft", "diamond_ores"), Compat.blockTag("minecraft", "emerald_ores"),
            Compat.blockTag("minecraft", "gold_ores"), Compat.blockTag("minecraft", "iron_ores"),
            Compat.blockTag("minecraft", "lapis_ores"), Compat.blockTag("minecraft", "redstone_ores"));

    public static boolean isNaturalTarget(BlockState state) {
        return state.is(BlockTags.DIRT)                  // grass block, dirt, podzol, mycelium, mud...
                || state.is(BlockTags.BASE_STONE_OVERWORLD) // stone, granite, diorite, andesite, tuff, deepslate
                || state.is(BlockTags.BASE_STONE_NETHER)    // netherrack, basalt, blackstone
                || state.is(BlockTags.SAND)                 // sand, red sand
                || isOre(state)                             // all ores (deepslate variants included)
                || state.is(BlockTags.SMALL_FLOWERS)
                || Compat.isTallFlower(state)
                || NATURAL_EXTRA.contains(state.getBlock());
    }

    /**
     * A block is crafted if it has a block-entity (chests, spawners,
     * furnaces, beds, bells, signs...), if it's in the explicit list, or if it
     * belongs to a family of crafted blocks that doesn't exist in natural
     * terrain (stairs, slabs, walls, fences, doors, rails, planks, wool...).
     */
    public static boolean isCrafted(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.hasBlockEntity()) {
            return true;
        }
        if (CRAFTED_BLOCKS.contains(state.getBlock())) {
            return true;
        }
        return Compat.isChain(state) // iron (+ every copper oxidation stage in 26.x)
                || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.SLABS)
                || state.is(BlockTags.WALLS)
                || state.is(BlockTags.FENCES)
                || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.RAILS)
                || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOOL)
                || state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.DOORS)
                || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.PRESSURE_PLATES)
                || state.is(BlockTags.BUTTONS);
    }

    /**
     * @return true if the position is within {@link #RADIUS} blocks of an
     *         crafted block and must therefore NOT be infected.
     */
    public boolean isProtected(ServerLevel level, BlockPos pos) {
        // Natural terrain is always a target, even flush against a structure.
        if (isNaturalTarget(level.getBlockState(pos))) {
            return false;
        }
        if (!anyNearbySectionHasCrafted(level, pos)) {
            return false;
        }

        // A nearby section contains crafted blocks somewhere: fine scan to
        // find out whether one of them is actually within the radius.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (isCrafted(level.getBlockState(cursor))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean anyNearbySectionHasCrafted(ServerLevel level, BlockPos pos) {
        Map<Long, Boolean> levelCache = sectionCache.computeIfAbsent(
                Compat.dimensionId(level), k -> new HashMap<>());

        int minSx = (pos.getX() - RADIUS) >> 4;
        int maxSx = (pos.getX() + RADIUS) >> 4;
        int minSy = (pos.getY() - RADIUS) >> 4;
        int maxSy = (pos.getY() + RADIUS) >> 4;
        int minSz = (pos.getZ() - RADIUS) >> 4;
        int maxSz = (pos.getZ() + RADIUS) >> 4;

        for (int sx = minSx; sx <= maxSx; sx++) {
            for (int sy = minSy; sy <= maxSy; sy++) {
                for (int sz = minSz; sz <= maxSz; sz++) {
                    long key = SectionPos.asLong(sx, sy, sz);
                    Boolean has = levelCache.get(key);
                    if (has == null) {
                        has = scanSection(level, sx, sy, sz);
                        levelCache.put(key, has);
                    }
                    if (has) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean scanSection(ServerLevel level, int sx, int sy, int sz) {
        int baseX = sx << 4;
        int baseY = sy << 4;
        int baseZ = sz << 4;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    cursor.set(baseX + x, baseY + y, baseZ + z);
                    if (isCrafted(level.getBlockState(cursor))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
