package net.emeraude.clearness.compat;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The calls whose shape differs between 1.21.1 and 26.x. This is the 1.21.1 side. */
public final class Compat {

    private Compat() {}

    public static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX, chunkZ);
    }

    public static ChunkPos chunkOf(BlockPos pos) {
        return new ChunkPos(pos);
    }

    /** The overworld clock, which days and the bed check are counted on. */
    public static long dayClock(ServerLevel level) {
        return level.getServer().overworld().getDayTime();
    }

    public static BlockPos worldSpawn(ServerLevel level) {
        return level.getSharedSpawnPos();
    }

    /** 1.21.1 has a single chain block and no chain tag. */
    public static boolean isChain(BlockState state) {
        return state.is(Blocks.CHAIN);
    }

    /** The tall_flowers tag exists in 1.21.1 only; earlier releases counted them as natural. */
    public static boolean isTallFlower(BlockState state) {
        return state.is(BlockTags.TALL_FLOWERS);
    }

    public static TagKey<Block> blockTag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public static boolean hasPermission(CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }

    public static boolean hasEntityTag(net.minecraft.world.entity.Entity entity, String tag) {
        return entity.getTags().contains(tag);
    }

    public static boolean getBoolean(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getBoolean(key);
    }

    public static int minY(ServerLevel level) {
        return level.getMinBuildHeight();
    }
}
