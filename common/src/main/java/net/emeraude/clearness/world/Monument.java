package net.emeraude.clearness.world;

import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.entity.item.ItemEntity;
import net.emeraude.clearness.Clearness;
import net.emeraude.clearness.ClearnessConfig;
import net.emeraude.clearness.compat.Compat;
import net.emeraude.clearness.state.ClearnessState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Optional;
import java.util.Random;

/**
 * The glass cathedral: built once per world ~600 blocks from spawn, with a
 * sealed chest (Pandora's box: a vanilla vault) on the altar at its centre. Opening the chest
 * releases the Clearness (deep sound + brief Darkness, no title).
 *
 * Each player receives, once, a "Seeker's Compass" pointing to the cathedral;
 * right-clicking it shows the remaining distance. Everything is server-side —
 * the compass needle is rendered by the vanilla client — so it works in
 * singleplayer and multiplayer, on both loaders, with no client mod.
 */
public final class Monument {

    private Monument() {}

    private static final Random RANDOM = new Random();
    private static final String COMPASS_TAG = "clearness_compass_given";

    // Cathedral dimensions (half-width, half-length, wall height).
    private static final int HW = 5;
    private static final int HL = 9;
    private static final int WALL = 7;

    // ---- Placement ---------------------------------------------------------

    /** Called on player join by each loader's event hook. */
    public static void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        placeIfNeeded(server);
        restoreChestIfLost(server);
        giveCompassOnce(server, player);
    }

    /** The sealed chest: a vanilla vault, locked and ornate, which no explosion can break. */
    private static BlockState sealedChest() {
        return Blocks.VAULT.defaultBlockState().setValue(VaultBlock.FACING, Direction.NORTH);
    }

    /** True at the altar while the box has not been opened: the block cannot be broken there. */
    public static boolean isSealed(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return false;
        }
        ClearnessState state = ClearnessState.get(level);
        return state.isMonumentPlaced() && !state.isCaptured() && state.getMonumentPos().equals(pos);
    }

    /**
     * Worlds from 2.0.0 and earlier held the box as a block of this mod's own, which no longer
     * exists: it loads as air. Put the vault back while the box is still sealed.
     */
    private static void restoreChestIfLost(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ClearnessState state = ClearnessState.get(level);
        if (!state.isMonumentPlaced() || state.isCaptured()) {
            return;
        }
        BlockPos pos = state.getMonumentPos();
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, sealedChest(), 2);
            Clearness.LOGGER.info("[Clearness] Sealed chest restored at {} (world from an earlier version)", pos);
        }
    }

    private static void placeIfNeeded(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ClearnessState state = ClearnessState.get(level);
        if (state.isMonumentPlaced() || state.isActive()) {
            return;
        }

        BlockPos spawn = Compat.worldSpawn(level);
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        int tx = spawn.getX() + (int) Math.round(Math.cos(angle) * ClearnessConfig.MONUMENT_DISTANCE);
        int tz = spawn.getZ() + (int) Math.round(Math.sin(angle) * ClearnessConfig.MONUMENT_DISTANCE);

        BlockPos chestPos = buildAt(level, tx, tz);
        state.setMonumentPlaced(chestPos);
        Clearness.LOGGER.info("[Clearness] Cathedral placed, chest at {}", chestPos);
    }

    /**
     * Force-generates the footprint chunks, finds the ground and builds there.
     * Also used by the /clearness cathedral test command.
     */
    public static BlockPos buildAt(ServerLevel level, int tx, int tz) {
        for (int cx = (tx - HW) >> 4; cx <= (tx + HW) >> 4; cx++) {
            for (int cz = (tz - HL) >> 4; cz <= (tz + HL) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        int groundY = level.getHeight(Heightmap.Types.WORLD_SURFACE, tx, tz) - 1;
        BlockPos chestPos = buildCathedral(level, new BlockPos(tx, groundY, tz));
        ClearnessState.get(level).setMonumentPlaced(chestPos);
        return chestPos;
    }

    /**
     * Builds a glass cathedral centred on {@code center} (floor level) and
     * returns the position of the sealed chest on the central altar.
     */
    private static BlockPos buildCathedral(ServerLevel level, BlockPos center) {
        final int ridge = WALL + HW;
        final BlockState glass = Blocks.GLASS.defaultBlockState();
        final BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
        final BlockState air = Blocks.AIR.defaultBlockState();
        final int ox = center.getX(), oy = center.getY(), oz = center.getZ();
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        // Clear interior volume so terrain doesn't poke through.
        for (int x = -HW; x <= HW; x++) {
            for (int z = -HL; z <= HL; z++) {
                for (int y = 1; y <= ridge + 8; y++) {
                    level.setBlock(p.set(ox + x, oy + y, oz + z), air, 2);
                }
            }
        }

        // Foundation + floor (3 deep, so it doesn't float on slopes).
        for (int x = -HW; x <= HW; x++) {
            for (int z = -HL; z <= HL; z++) {
                for (int y = 0; y >= -2; y--) {
                    level.setBlock(p.set(ox + x, oy + y, oz + z), glass, 2);
                }
            }
        }

        // Long walls with tall pointed windows.
        for (int side : new int[]{-HW, HW}) {
            for (int z = -HL; z <= HL; z++) {
                for (int y = 1; y <= WALL; y++) {
                    boolean window = Math.floorMod(z + HL, 3) == 1 && Math.abs(z) < HL - 1
                            && y >= 2 && y <= WALL - 1;
                    level.setBlock(p.set(ox + side, oy + y, oz + z), window ? pane : glass, 2);
                }
            }
        }

        // End walls.
        for (int endZ : new int[]{-HL, HL}) {
            for (int x = -HW; x <= HW; x++) {
                for (int y = 1; y <= WALL; y++) {
                    level.setBlock(p.set(ox + x, oy + y, oz + endZ), glass, 2);
                }
            }
        }

        // Gabled roof + closed triangular gables at both ends.
        for (int x = -HW; x <= HW; x++) {
            int roofY = WALL + (HW - Math.abs(x));
            for (int z = -HL; z <= HL; z++) {
                level.setBlock(p.set(ox + x, oy + roofY, oz + z), glass, 2);
            }
            for (int endZ : new int[]{-HL, HL}) {
                for (int y = WALL + 1; y <= roofY; y++) {
                    level.setBlock(p.set(ox + x, oy + y, oz + endZ), glass, 2);
                }
            }
        }

        // Front doorway: 3 wide, 4 tall.
        for (int x = -1; x <= 1; x++) {
            for (int y = 1; y <= 4; y++) {
                level.setBlock(p.set(ox + x, oy + y, oz - HL), air, 2);
            }
        }

        // Rose window on the front gable: a ring of panes.
        int rcy = WALL + 2;
        for (int x = -2; x <= 2; x++) {
            for (int y = rcy - 2; y <= rcy + 2; y++) {
                int d2 = x * x + (y - rcy) * (y - rcy);
                if (d2 >= 2 && d2 <= 4) {
                    level.setBlock(p.set(ox + x, oy + y, oz - HL), pane, 2);
                }
            }
        }

        // Nave pillars (arcade), skipping the central altar bay.
        for (int px : new int[]{-3, 3}) {
            for (int pz : new int[]{-6, -3, 3, 6}) {
                for (int y = 1; y <= WALL; y++) {
                    level.setBlock(p.set(ox + px, oy + y, oz + pz), glass, 2);
                }
            }
        }

        // Central altar (3x3, raised) with the sealed chest facing the door.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlock(p.set(ox + x, oy + 1, oz + z), glass, 2);
            }
        }
        BlockPos chestPos = new BlockPos(ox, oy + 2, oz);
        level.setBlock(chestPos, sealedChest(), 2);

        // Steeple above the front gable.
        for (int y = ridge; y <= ridge + 6; y++) {
            level.setBlock(p.set(ox, oy + y, oz - HL), glass, 2);
        }
        level.setBlock(p.set(ox - 1, oy + ridge + 4, oz - HL), glass, 2);
        level.setBlock(p.set(ox + 1, oy + ridge + 4, oz - HL), glass, 2);

        return chestPos;
    }

    // ---- Capture (opening the box) ----------------------------------------

    /** Called when a player right-clicks the sealed chest. */
    public static void capture(ServerLevel level, ServerPlayer player, BlockPos triggerPos) {
        ClearnessState state = ClearnessState.get(level);
        if (state.isCaptured()) {
            return;
        }
        state.setCaptured();

        BlockPos origin = state.isMonumentPlaced() ? state.getMonumentPos() : triggerPos;
        if (!Clearness.MANAGER.isRunning(level)) {
            Clearness.MANAGER.seedClearness(level, origin);
        }

        // Cue: deep boom + brief screen-darkening (vanilla Darkness effect).
        level.playSound(null, origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5,
                SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 3.0f, 0.5f);
        double r2 = (double) ClearnessConfig.CAPTURE_DARKNESS_RADIUS * ClearnessConfig.CAPTURE_DARKNESS_RADIUS;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5) <= r2) {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS,
                        ClearnessConfig.CAPTURE_DARKNESS_SECONDS * 20, 0, false, false));
            }
        }

        Clearness.LOGGER.info("[Clearness] Pandora's box opened by {}", player.getName().getString());
    }

    // ---- Seeker's compass --------------------------------------------------

    private static void giveCompassOnce(MinecraftServer server, ServerPlayer player) {
        ClearnessState state = ClearnessState.get(server.overworld());
        if (!state.isMonumentPlaced() || Compat.hasEntityTag(player, COMPASS_TAG)) {
            return;
        }
        player.addTag(COMPASS_TAG);
        ItemStack compass = makeCompass(state.getMonumentPos());
        if (!player.addItem(compass)) {
            // Full inventory: at the player's feet. (Player.drop changed shape in 26.3.)
            player.level().addFreshEntity(new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), compass));
        }
    }

    private static ItemStack makeCompass(BlockPos target) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(
                Optional.of(GlobalPos.of(Level.OVERWORLD, target)), false));
        CustomData.set(DataComponents.CUSTOM_DATA, compass, tagOf());
        compass.set(DataComponents.CUSTOM_NAME,
                Component.literal("Seeker's Compass").withStyle(ChatFormatting.AQUA));
        return compass;
    }

    private static net.minecraft.nbt.CompoundTag tagOf() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putBoolean("clearness_compass", true);
        return tag;
    }

    public static boolean isSeekerCompass(ItemStack stack) {
        if (!stack.is(Items.COMPASS)) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && Compat.getBoolean(data.copyTag(), "clearness_compass");
    }

    /** Called by each loader when a player right-clicks with an item. */
    public static void onCompassUse(ServerLevel level, ServerPlayer player) {
        ClearnessState state = ClearnessState.get(level);

        Component msg;
        if (state.isCaptured()) {
            msg = Component.literal("The Clearness has awoken.").withStyle(ChatFormatting.DARK_GRAY);
        } else if (!state.isMonumentPlaced()) {
            msg = Component.literal("The needle spins, lost.").withStyle(ChatFormatting.DARK_GRAY);
        } else if (!level.dimension().equals(Level.OVERWORLD)) {
            msg = Component.literal("The anomaly lies in the Overworld.").withStyle(ChatFormatting.GRAY);
        } else {
            BlockPos m = state.getMonumentPos();
            double dx = m.getX() - player.getX();
            double dz = m.getZ() - player.getZ();
            int dist = (int) Math.sqrt(dx * dx + dz * dz);
            msg = Component.literal("Anomaly  ·  " + dist + " m").withStyle(ChatFormatting.AQUA);
        }
        player.sendSystemMessage(msg, true); // action bar
    }
}
