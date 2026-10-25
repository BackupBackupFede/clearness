package net.emeraude.clearness.command;

import net.emeraude.clearness.compat.Compat;
import com.mojang.brigadier.CommandDispatcher;
import net.emeraude.clearness.Clearness;
import net.emeraude.clearness.world.Monument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * Operator command tree, shared by both loaders. Each loader registers it on
 * its own command event.
 */
public final class ClearnessCommand {

    private ClearnessCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("clearness")
                // Operators only. 26.x replaced integer permission levels with
                // named permission checks; gamemasters == the old level 2.
                .requires(source -> Compat.hasPermission(source, 2))
                .then(Commands.literal("start")
                        .executes(ctx -> start(ctx.getSource(), BlockPos.containing(ctx.getSource().getPosition())))
                        .then(Commands.argument("position", BlockPosArgument.blockPos())
                                .executes(ctx -> start(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "position")))))
                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            Clearness.MANAGER.stopClearness(source.getLevel());
                            source.sendSuccess(() -> Component.literal("Stopped."), true);
                            return 1;
                        }))
                .then(Commands.literal("cathedral")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            BlockPos pos = BlockPos.containing(source.getPosition());
                            BlockPos chest = Monument.buildAt(source.getLevel(), pos.getX(), pos.getZ());
                            source.sendSuccess(() -> Component.literal(
                                    "Cathedral built, chest at " + chest), true);
                            return 1;
                        }))
                .then(Commands.literal("check")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            ServerLevel level = source.getLevel();
                            BlockPos pos = BlockPos.containing(source.getPosition());
                            boolean blocked = Clearness.MANAGER.isProtectedAt(level, pos);
                            String verdict = Clearness.MANAGER.describeProtection(level, pos);
                            source.sendSuccess(() -> Component.literal(pos + " : " + verdict), false);
                            return blocked ? 1 : 0;
                        }))
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            ServerLevel level = source.getLevel();
                            boolean running = Clearness.MANAGER.isRunning(level);
                            int frontier = Clearness.MANAGER.getFrontierSize(level);
                            long days = Clearness.MANAGER.getDaysElapsed(level);
                            double speed = Clearness.MANAGER.getSpeedMultiplier(level);
                            source.sendSuccess(() -> Component.literal(
                                    (running ? "Active" : "Inactive")
                                            + " - day " + days
                                            + " - speed x" + String.format(Locale.ROOT, "%.1f", speed)
                                            + " - frontier: " + frontier), false);
                            return 1;
                        })));
    }

    private static int start(CommandSourceStack source, BlockPos pos) {
        ServerLevel level = source.getLevel();
        if (Clearness.MANAGER.isRunning(level)) {
            source.sendSuccess(() -> Component.literal("Already running."), false);
            return 0;
        }
        Clearness.MANAGER.seedClearness(level, pos);
        source.sendSuccess(() -> Component.literal("Started at " + pos + "."), true);
        return 1;
    }
}
