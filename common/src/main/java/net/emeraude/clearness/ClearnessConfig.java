package net.emeraude.clearness;

/**
 * Hard-coded configuration. Could later be wired to a JSON file in the config/
 * folder to make it tweakable without recompiling.
 */
public final class ClearnessConfig {

    private ClearnessConfig() {}

    /** Max number of blocks turned to glass per propagation wave (before the
     *  daily speed multiplier is applied). */
    public static final int MAX_CONVERSIONS_PER_WAVE = 800;

    /** Absolute safety cap: whatever the daily multiplier, a wave never
     *  exceeds this budget (protects server TPS in the late game). */
    public static final int MAX_CONVERSIONS_HARD_CAP = 3000;

    /** Chance (0-1) that a frontier block infects each of its 6 neighbours on
     *  a given evaluation (before the daily multiplier, capped at 1.0). */
    public static final double SPREAD_CHANCE = 0.55;

    /** Ticks between two propagation waves (1 = every tick). */
    public static final int TICKS_BETWEEN_WAVES = 1;

    /** Acceleration per in-game day elapsed since the Clearness started:
     *  speed multiplier = 1 + DAILY_ACCELERATION x days elapsed. */
    public static final double DAILY_ACCELERATION = 1.0;

    /** Ceiling of the daily multiplier. */
    public static final double MAX_SPEED_MULTIPLIER = 20.0;

    /** If true, bedrock is convertible like any other block. */
    public static final boolean INFECT_BEDROCK = true;

    /** Structure protection margins — we only protect the actual volume of the
     *  structure's pieces, plus a small ground trail beneath them. */
    public static final int STRUCTURE_TRAIL_BELOW = 3;
    public static final int STRUCTURE_MARGIN_SIDES = 1;
    public static final int STRUCTURE_MARGIN_ABOVE = 1;

    /** Player pursuit: every PURSUIT_INTERVAL_TICKS, new outbreaks appear near
     *  each player so the Clearness hounds them wherever they go. */
    public static final int PURSUIT_INTERVAL_TICKS = 40; // 2 seconds
    public static final int PURSUIT_MIN_RADIUS = 8;
    public static final int PURSUIT_MAX_RADIUS = 32;
    public static final int PURSUIT_CLUSTERS = 2;

    /** The cathedral: distance (in blocks) from world spawn where it is built. */
    public static final int MONUMENT_DISTANCE = 600;

    /** On capture: Darkness effect duration and the radius of players it hits. */
    public static final int CAPTURE_DARKNESS_SECONDS = 8;
    public static final int CAPTURE_DARKNESS_RADIUS = 48;

    /** Every seeded outbreak converts an organic sphere of this radius. */
    public static final int SEED_CLUSTER_RADIUS = 3;

    /** Mini-outbreak when the player uses an interaction block. */
    public static final int MINI_OUTBREAK_CLUSTERS = 10;
    public static final int MINI_OUTBREAK_MIN_RADIUS = 2;
    public static final int MINI_OUTBREAK_MAX_RADIUS = 12;

    /** Large outbreak when the player sleeps and skips the night. */
    public static final int BED_OUTBREAK_CLUSTERS = 60;
    public static final int BED_OUTBREAK_MIN_RADIUS = 2;
    public static final int BED_OUTBREAK_MAX_RADIUS = 32;

    /** Radius (in chunks) of the initial glass patch at "patient zero". */
    public static final int SEED_RADIUS_CHUNKS = 2;
}
