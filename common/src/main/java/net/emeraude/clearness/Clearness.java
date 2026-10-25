package net.emeraude.clearness;

import net.emeraude.clearness.logic.ClearnessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Loader-agnostic entry point — TIER A (server-side) logic goes here.
 *
 * <p>Both loader modules compile this source in directly and call {@link #init(Path)}. Anything
 * that runs on a dedicated server belongs in this class or below it; nothing here may touch
 * client-only Minecraft classes, or a dedicated server will crash on class load.
 */
public final class Clearness {

    public static final String MOD_ID = "clearness";
    public static final Logger LOGGER = LoggerFactory.getLogger("Clearness");

    /** Single manager instance: every hook delegates to it. */
    public static final ClearnessManager MANAGER = new ClearnessManager();

    private static boolean initialized = false;
    private static Path configDir = Path.of("config");

    private Clearness() {}

    /** Called once from each loader's entry point, as early as possible, with the loader's config folder. */
    public static synchronized void init(Path configDir) {
        if (initialized) return;
        initialized = true;
        Clearness.configDir = configDir;

        LOGGER.info("{} loaded{}", MOD_ID, debug() ? " (DEBUG ON)" : "");
    }

    public static Path configDir() {
        return configDir;
    }

    /**
     * Test-only features (a debug command, a fake second player...). Off in normal use, on when any
     * of these is present:
     * <ul>
     *   <li>the self-test switch ({@code <MODID>_SELFTEST=true}) — which also HALTS the server, so it
     *       is no use for playing;</li>
     *   <li>{@code <MODID>_DEBUG=true} in the environment — a Gradle run inherits it;</li>
     *   <li>{@code -D<modid>.debug=true} on the JVM;</li>
     *   <li>an empty file {@code config/<modid>.debug} — for a launcher that cannot take JVM
     *       arguments, which is the case on this machine.</li>
     * </ul>
     * Gate debug commands behind an operator level as well: the file can be left behind on a server.
     */
    public static boolean debug() {
        String env = MOD_ID.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") + "_DEBUG";
        return ClearnessSelfTest.requested()
            || "true".equalsIgnoreCase(System.getenv(env))
            || Boolean.getBoolean(MOD_ID + ".debug")
            || Files.exists(configDir.resolve(MOD_ID + ".debug"));
    }
}
