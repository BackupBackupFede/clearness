package net.emeraude.clearness;

import com.mojang.authlib.GameProfile;
import net.emeraude.clearness.state.ClearnessState;
import net.emeraude.clearness.world.Monument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Behaviour check for a dedicated server, off unless {@code CLEARNESS_SELFTEST=true}. Every
 * gesture goes through the real entry point (the player list, the game mode, the dispatcher), so
 * the mixins are part of what is checked. Expects a fresh world: selftest.ps1 -FreshWorld.
 */
public final class ClearnessSelfTest {

    private static final String SWITCH = Clearness.MOD_ID.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") + "_SELFTEST";

    private ClearnessSelfTest() {}

    public static boolean requested() {
        return "true".equalsIgnoreCase(System.getenv(SWITCH));
    }

    /** Second pass of selftest.ps1 -Restart: the same world, after a real stop and start. */
    private static boolean afterRestart() {
        return "2".equals(System.getenv(SWITCH + "_PHASE"));
    }

    public static void run(MinecraftServer server) {
        List<String> failures = new ArrayList<>();
        if (afterRestart()) {
            ServerLevel level = server.overworld();
            ClearnessState state = ClearnessState.get(level);
            check(failures, "state survived the restart",
                !state.isMonumentPlaced() ? "the cathedral was forgotten"
                    : !state.isCaptured() ? "the opened chest was forgotten"
                    : !Clearness.MANAGER.isRunning(level) ? "the Clearness stopped" : null);
            Clearness.LOGGER.info("[SELFTEST] RESULT {}", failures.isEmpty() ? "PASS" : "FAIL " + failures);
            server.halt(false);
            return;
        }
        try {
            ServerLevel level = server.overworld();
            check(failures, "commands", server.getCommands().getDispatcher().getRoot().getChild("clearness") == null ? "/clearness not registered" : null);
            check(failures, "hooks applied", hooks());
            ServerPlayer player = player(server, level);
            Monument.onPlayerJoin(server, player); // what the join hook calls
            check(failures, "cathedral on first join", cathedral(level));
            check(failures, "seeker's compass", compass(level, player));
            check(failures, "lost chest restored", restore(server, level, player));
            check(failures, "sealed chest resists", sealed(level, player));
            check(failures, "capture releases the Clearness", capture(server, level, player));
            check(failures, "propagation", propagation(server, level));
            check(failures, "workstation punishment", workstation(level, player));
            check(failures, "bed punishment", bed(server, level, player));
        } catch (Throwable t) {
            Clearness.LOGGER.error("[SELFTEST] crashed", t);
            failures.add("crash: " + t);
        }
        Clearness.LOGGER.info("[SELFTEST] RESULT {}", failures.isEmpty() ? "PASS" : "FAIL " + failures);
        server.halt(false);
    }

    private static void check(List<String> failures, String name, String error) {
        if (error == null) {
            Clearness.LOGGER.info("[SELFTEST] {} OK", name);
        } else {
            Clearness.LOGGER.error("[SELFTEST] {} FAILED: {}", name, error);
            failures.add(name + ": " + error);
        }
    }

    // --- checks ----------------------------------------------------------------------------------

    private static String cathedral(ServerLevel level) {
        ClearnessState state = ClearnessState.get(level);
        if (!state.isMonumentPlaced()) return "no cathedral after the first join";
        BlockPos chest = state.getMonumentPos();
        if (!level.getBlockState(chest).is(Blocks.VAULT)) return "no sealed chest (vault) at " + chest + ": " + level.getBlockState(chest);
        BlockPos spawn = net.emeraude.clearness.compat.Compat.worldSpawn(level);
        double d = Math.sqrt(Math.pow(chest.getX() - spawn.getX(), 2) + Math.pow(chest.getZ() - spawn.getZ(), 2));
        if (Math.abs(d - ClearnessConfig.MONUMENT_DISTANCE) > 3) return "cathedral " + (int) d + " blocks from spawn";
        return level.getBlockState(chest.below()).is(Blocks.GLASS) ? null : "no glass altar under the chest";
    }

    private static String compass(ServerLevel level, ServerPlayer player) {
        ItemStack found = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (Monument.isSeekerCompass(player.getInventory().getItem(i))) found = player.getInventory().getItem(i);
        }
        if (found.isEmpty()) return "no Seeker's Compass given on join";
        // through the game mode, as a right-click in the air would
        player.gameMode.useItem(player, level, found, InteractionHand.MAIN_HAND);
        return null;
    }

    private static String restore(MinecraftServer server, ServerLevel level, ServerPlayer player) {
        BlockPos chest = ClearnessState.get(level).getMonumentPos();
        level.setBlock(chest, Blocks.AIR.defaultBlockState(), 2); // what a 2.0.0 world looks like
        Monument.onPlayerJoin(server, player);
        return level.getBlockState(chest).is(Blocks.VAULT) ? null : "the sealed chest was not put back";
    }

    private static String sealed(ServerLevel level, ServerPlayer player) {
        BlockPos chest = ClearnessState.get(level).getMonumentPos();
        boolean broken = player.gameMode.destroyBlock(chest);
        if (broken || !level.getBlockState(chest).is(Blocks.VAULT)) return "a survival player broke the sealed chest";
        if (Clearness.MANAGER.isRunning(level)) return "running before the chest was opened";
        return null;
    }

    private static String capture(MinecraftServer server, ServerLevel level, ServerPlayer player) {
        BlockPos chest = ClearnessState.get(level).getMonumentPos();
        player.teleportTo(chest.getX() + 0.5, chest.getY() + 1, chest.getZ() + 3.5);
        InteractionResult r = player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false));
        if (!r.consumesAction()) return "right-click on the chest returned " + r;
        if (!ClearnessState.get(level).isCaptured()) return "not captured";
        if (!Clearness.MANAGER.isRunning(level)) return "the Clearness did not start";
        return Clearness.MANAGER.getFrontierSize(level) > 0 ? null : "empty frontier after the seed";
    }

    /**
     * Watched on the land at world spawn, not around the cathedral: the cathedral can stand on the
     * ocean, where nothing converts (fluids never do) and a radius around it stays unchanged.
     */
    private static String propagation(MinecraftServer server, ServerLevel level) {
        BlockPos spawn = land(level, net.emeraude.clearness.compat.Compat.worldSpawn(level));
        Clearness.MANAGER.outbreak(level, spawn, 10, 2, 12);
        int before = glass(level, spawn, 32);
        for (int i = 0; i < 40; i++) Clearness.MANAGER.onServerTick(server);
        int after = glass(level, spawn, 32);
        return after > before ? null : "no new glass after 40 ticks around spawn (" + before + " -> " + after + ")";
    }

    private static String workstation(ServerLevel level, ServerPlayer player) {
        BlockPos base = land(level, net.emeraude.clearness.compat.Compat.worldSpawn(level).offset(48, 0, 0));
        BlockPos table = base.above();
        level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
        int before = glass(level, table, 16);
        player.teleportTo(table.getX() + 0.5, table.getY() + 1, table.getZ() + 2.5);
        player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(table), Direction.UP, table, false));
        int after = glass(level, table, 16);
        return after > before ? null : "using a crafting table spread nothing (" + before + " -> " + after + ")";
    }

    private static String bed(MinecraftServer server, ServerLevel level, ServerPlayer player) {
        BlockPos base = land(level, net.emeraude.clearness.compat.Compat.worldSpawn(level).offset(-48, 0, 0));
        BlockPos bedPos = base.above();
        level.setBlock(bedPos, block("minecraft:red_bed").defaultBlockState(), 2);
        int before = glass(level, bedPos, 34);
        player.teleportTo(bedPos.getX() + 0.5, bedPos.getY() + 1, bedPos.getZ() + 0.5);
        player.startSleeping(bedPos);
        // morning: the night was skipped
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 1000");
        player.stopSleepInBed(false, true);
        int after = glass(level, bedPos, 34);
        return after > before ? null : "waking up after a skipped night spread nothing (" + before + " -> " + after + ")";
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Blocks resolved by id: some Blocks.* fields were renamed or regrouped in 26.x. */
    private static net.minecraft.world.level.block.Block block(String id) {
        for (net.minecraft.world.level.block.Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b)).equals(id)) return b;
        }
        throw new IllegalArgumentException("unknown block " + id);
    }

    /**
     * Dry ground near {@code around}: the cathedral, and anything placed relative to it, can stand
     * on the ocean, where nothing converts. Searched outward in rings until a surface is not a fluid.
     */
    private static BlockPos land(ServerLevel level, BlockPos around) {
        for (int r = 0; r <= 128; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) {
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos top = surface(level, around.offset(dx, 0, dz));
                    if (level.getBlockState(top).getFluidState().isEmpty() && level.getBlockState(top.above()).getFluidState().isEmpty()) {
                        return top;
                    }
                }
            }
        }
        return surface(level, around);
    }

    private static BlockPos surface(ServerLevel level, BlockPos at) {
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        return new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 1, at.getZ());
    }

    private static int glass(ServerLevel level, BlockPos center, int r) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (level.getBlockState(p.set(center.getX() + x, center.getY() + y, center.getZ() + z)).is(Blocks.GLASS)) n++;
                }
            }
        }
        return n;
    }

    /**
     * Mixin merges each handler into its target class under a name that keeps ours
     * ("...clearness$join"). Finding them proves every hook applied on this loader and line,
     * including the ones a connectionless player cannot reach (the real join).
     */
    private static String hooks() {
        String[][] expected = {
            {"net.minecraft.server.players.PlayerList", "clearness$join"},
            {"net.minecraft.server.MinecraftServer", "clearness$tick"},
            {"net.minecraft.server.level.ServerPlayerGameMode", "clearness$useOn"},
            {"net.minecraft.server.level.ServerPlayerGameMode", "clearness$use"},
            {"net.minecraft.server.level.ServerPlayerGameMode", "clearness$destroy"},
            {"net.minecraft.server.level.ServerPlayer", "clearness$beforeWake"},
            {"net.minecraft.server.level.ServerPlayer", "clearness$afterWake"},
        };
        Class<?>[] targets = {net.minecraft.server.players.PlayerList.class, MinecraftServer.class,
            net.minecraft.server.level.ServerPlayerGameMode.class, net.minecraft.server.level.ServerPlayerGameMode.class,
            net.minecraft.server.level.ServerPlayerGameMode.class, ServerPlayer.class, ServerPlayer.class};
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < expected.length; i++) {
            boolean found = false;
            for (java.lang.reflect.Method m : targets[i].getDeclaredMethods()) {
                if (m.getName().contains(expected[i][1])) found = true;
            }
            if (!found) missing.add(expected[i][1]);
        }
        return missing.isEmpty() ? null : "not applied: " + missing;
    }

    /** A player with a connection that has no channel: vanilla queues its packets. */
    private static ServerPlayer player(MinecraftServer server, ServerLevel level) {
        UUID id = UUID.randomUUID();
        GameProfile profile = new GameProfile(id, "st_" + id.toString().substring(0, 8));
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), player, CommonListenerCookie.createInitial(profile, false));
        return player;
    }
}
