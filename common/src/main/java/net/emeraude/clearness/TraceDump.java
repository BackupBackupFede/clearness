package net.emeraude.clearness;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.block.state.BlockState;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reference trace of the protection rules: for every block of two fixed zones, the propagation
 * verdict ('.' not convertible, 'T' natural target, 't' other target, 'g' ground trail,
 * 's' structure volume, 'a' near a crafted block). Written to trace/, then the server halts.
 */
public final class TraceDump {

    private TraceDump() {}

    public static boolean requested() {
        return System.getenv("CLEARNESS_TRACE") != null;
    }

    public static void run(MinecraftServer server) {
        StringBuilder report = new StringBuilder();
        try {
            ServerLevel level = server.overworld();
            Method convertible = Clearness.MANAGER.getClass().getDeclaredMethod("isConvertible", BlockState.class);
            convertible.setAccessible(true);
            Path out = Path.of("trace");
            Files.createDirectories(out);

            BlockPos village = level.findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false);
            BlockPos mineshaft = level.findNearestMapStructure(StructureTags.MINESHAFT, BlockPos.ZERO, 100, false);
            report.append("seed ").append(level.getSeed()).append('\n');
            zone(report, out, level, convertible, "village", village, 80, 40, 130);
            zone(report, out, level, convertible, "mineshaft", mineshaft, 48, -60, 60);
            report.append("RESULT DONE\n");
        } catch (Throwable t) {
            report.append("RESULT CRASH ").append(t).append('\n');
            Clearness.LOGGER.error("[TRACE] crashed", t);
        }
        try {
            Files.writeString(Path.of("trace", "report.txt"), report.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        Clearness.LOGGER.info("[TRACE]\n{}", report);
        server.halt(false);
    }

    private static void zone(StringBuilder report, Path out, ServerLevel level, Method convertible, String name,
                             BlockPos center, int half, int minY, int maxY) throws Exception {
        if (center == null) {
            report.append(name).append(" NOT FOUND\n");
            return;
        }
        int x0 = center.getX() - half, x1 = center.getX() + half;
        int z0 = center.getZ() - half, z1 = center.getZ() + half;
        for (int cx = (x0 >> 4) - 1; cx <= (x1 >> 4) + 1; cx++) {
            for (int cz = (z0 >> 4) - 1; cz <= (z1 >> 4) + 1; cz++) {
                level.getChunk(cx, cz);
            }
        }
        MessageDigest verdicts = MessageDigest.getInstance("SHA-256");
        MessageDigest world = MessageDigest.getInstance("SHA-256");
        Map<Character, Long> counts = new TreeMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        try (OutputStream file = Files.newOutputStream(out.resolve(name + ".bin"))) {
            for (int y = minY; y <= maxY; y++) {
                byte[] row = new byte[(x1 - x0 + 1) * (z1 - z0 + 1)];
                int i = 0;
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        p.set(x, y, z);
                        BlockState state = level.getBlockState(p);
                        world.update(String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())).getBytes(StandardCharsets.UTF_8));
                        char v;
                        if (!(boolean) convertible.invoke(Clearness.MANAGER, state)) {
                            v = '.';
                        } else {
                            String d = Clearness.MANAGER.describeProtection(level, p);
                            if (d.startsWith("TARGET (natural")) v = 'T';
                            else if (d.startsWith("TARGET")) v = 't';
                            else if (d.contains("ground trail")) v = 'g';
                            else if (d.contains("structure volume")) v = 's';
                            else if (d.contains("near a crafted")) v = 'a';
                            else v = '?';
                        }
                        counts.merge(v, 1L, Long::sum);
                        row[i++] = (byte) v;
                    }
                }
                verdicts.update(row);
                file.write(row);
            }
        }
        report.append(name).append(" center ").append(center.toShortString())
            .append(" box x[").append(x0).append(',').append(x1).append("] y[").append(minY).append(',').append(maxY)
            .append("] z[").append(z0).append(',').append(z1).append("]\n")
            .append(name).append(" world   ").append(HexFormat.of().formatHex(world.digest())).append('\n')
            .append(name).append(" verdict ").append(HexFormat.of().formatHex(verdicts.digest())).append('\n')
            .append(name).append(" counts  ").append(counts).append('\n');
    }
}
