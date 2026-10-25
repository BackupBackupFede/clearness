package net.emeraude.clearness.compat;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** The calls whose shape differs between 1.21.1 and 26.x. This is the 26.x side. */
public final class Compat {

    private Compat() {}

    public static String dimensionId(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ChunkPos.pack(chunkX, chunkZ);
    }

    public static ChunkPos chunkOf(BlockPos pos) {
        return ChunkPos.containing(pos);
    }

    /** The overworld clock, which days and the bed check are counted on. */
    public static long dayClock(ServerLevel level) {
        return level.getOverworldClockTime();
    }

    public static BlockPos worldSpawn(ServerLevel level) {
        return level.getLevelData().getRespawnData().pos();
    }

    /** 26.x has iron and copper chains, grouped in a tag. */
    public static boolean isChain(BlockState state) {
        return state.is(BlockTags.CHAINS);
    }

    /** No tall_flowers tag in 26.x: tall flowers fall through to the other rules, as in 2.0.0. */
    public static boolean isTallFlower(BlockState state) {
        return false;
    }

    public static TagKey<Block> blockTag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(namespace, path));
    }

    public static boolean hasPermission(CommandSourceStack source, int level) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }

    public static boolean hasEntityTag(net.minecraft.world.entity.Entity entity, String tag) {
        return entity.entityTags().contains(tag);
    }

    public static boolean getBoolean(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getBooleanOr(key, false);
    }

    public static int minY(ServerLevel level) {
        return level.getMinY();
    }
}
