package dmr.DragonMounts.config;

import dmr.DragonMounts.config.annotations.Config;
import dmr.DragonMounts.config.annotations.RangeConstraint;
import dmr.DragonMounts.server.entity.DragonConstants;
import net.neoforged.neoforge.common.ModConfigSpec;

public class ServerConfig {
    public static final ModConfigSpec MOD_CONFIG_SPEC;

    @Config(key = "hatch_time", comment = "Time in seconds for a dragon egg to hatch.")
    @RangeConstraint(min = 0, max = Integer.MAX_VALUE)
    public static Long HATCH_TIME_CONFIG = DragonConstants.HATCH_TIME;

    @Config(key = "growth_time", comment = "Time in seconds for a dragon to grow.")
    @RangeConstraint(min = 0, max = Integer.MAX_VALUE)
    public static Long GROWTH_TIME_CONFIG = DragonConstants.GROWTH_TIME;

    @Config(
            key = "allow_egg_override",
            comment = {
                "Allow the vanilla ender egg to be interacted with? (Hatchable)",
                "Useful to help with mod compatibility"
            },
            category = "eggs")
    public static boolean ALLOW_EGG_OVERRIDE = true;

    @Config(
            key = "replenish_eggs",
            comment = {
                "Should Ender Dragon Eggs replenish on the exit portal after a respawned dragon is defeated?",
                "Useful for multiplayer scenarios."
            },
            category = "eggs")
    public static boolean REPLENISH_EGGS = true;

    @Config(key = "allow_hybridization", comment = "Allow hybridization between dragons.", category = "eggs")
    public static boolean ALLOW_HYBRIDIZATION = true;

    @Config(
            key = "habitat_offspring",
            comment = "Offspring from breeding can turn into dragon type matching current environment.",
            category = "eggs")
    public static boolean HABITAT_OFFSPRING = true;

    @Config(
            key = "enable_blank_egg",
            comment = "Enable blank dragon eggs which changes based on the environment.",
            category = "eggs")
    public static boolean ENABLE_BLANK_EGG = false;

    @Config(key = "enable_natural_dragon_spawns", comment = "Enable or disable natural dragon spawns.")
    public static boolean ENABLE_NATURAL_DRAGON_SPAWNS = false;

    @Config(
            key = "dragon_history_size",
            comment =
                    "The maximum number of dragons to keep track of in the dragon history. This allows recalling missing dragons through commands. Larger values may increase world save size.")
    @RangeConstraint(min = 1, max = Integer.MAX_VALUE)
    public static int DRAGON_HISTORY_SIZE = 20;

    @Config(key = "base_health", comment = "Base health of all dragons.", category = "base_stats")
    @RangeConstraint(min = 1.0)
    public static double BASE_HEALTH = DragonConstants.BASE_HEALTH;

    @Config(key = "health_regen", comment = "Passive health regen value for dragons.", category = "base_stats")
    @RangeConstraint(min = 0)
    public static double HEALTH_REGEN = 1.0;

    @Config(key = "base_damage", comment = "Base damage of all dragons.", category = "base_stats")
    @RangeConstraint(min = 1.0)
    public static double BASE_DAMAGE = DragonConstants.BASE_DAMAGE;

    /** @deprecated Use {@code base_walking_speed} instead. */
    @Config(key = "base_speed", comment = "Base movement speed for all dragons.", category = "base_stats")
    @RangeConstraint(min = 0)
    @Deprecated()
    public static double BASE_SPEED = 1.0;

    @Config(key = "base_walking_speed", comment = "Base walking speed for all dragons.", category = "base_stats")
    @RangeConstraint(min = 0)
    public static double BASE_WALKING_SPEED = 1.0;

    @Config(key = "base_flying_speed", comment = "Base flying speed for all dragons.", category = "base_stats")
    @RangeConstraint(min = 0)
    public static double BASE_FLYING_SPEED = 1.0;

    @Config(key = "base_swimming_speed", comment = "Base swimming speed for all dragons.", category = "base_stats")
    @RangeConstraint(min = 0)
    public static double BASE_SWIMMING_SPEED = 1.0;

    @Config(key = "size_modifier", comment = "Size modifier for all dragons.", category = "base_stats")
    @RangeConstraint(min = 0.01)
    public static double SIZE_MODIFIER = 1.0;

    @Config(
            key = "enable_random_stats",
            comment = "Whether to enable random stats for dragons.",
            category = {"base_stats", "random_stats"})
    public static boolean ENABLE_RANDOM_STATS = true;

    @Config(
            key = "upper_max_health",
            comment = "The maximum health bonus for dragons with random stats.",
            category = {"base_stats", "random_stats"})
    @RangeConstraint(min = 0)
    public static int UPPER_MAX_HEALTH = 10;

    @Config(
            key = "upper_damage",
            comment = "The maximum damage bonus for dragons with random stats.",
            category = {"base_stats", "random_stats"})
    @RangeConstraint(min = 0)
    public static double UPPER_DAMAGE = 5.0;

    @Config(
            key = "upper_speed",
            comment = "The maximum speed bonus for dragons with random stats.",
            category = {"base_stats", "random_stats"})
    @RangeConstraint(min = 0)
    public static double UPPER_SPEED = 0.2;

    @Config(
            key = "lower_max_health",
            comment = "The minimum health penalty for dragons with random stats.",
            category = {"base_stats", "random_stats"})
    @RangeConstraint(min = -Integer.MAX_VALUE, max = -1)
    public static int LOWER_MAX_HEALTH = -5;

    @Config(
            key = "lower_damage",
            comment = "The minimum damage penalty for dragons with random stats.",
            category = {"base_stats", "random_stats"})
    @RangeConstraint(min = -Double.MAX_VALUE, max = -1)
    public static double LOWER_DAMAGE = -2.5;

    @Config(
            key = "lower_speed",
            comment = "The minimum speed penalty for dragons with random stats.",
            category = {"base_stats", "random_stats"})
    @RangeConstraint(min = -Double.MAX_VALUE, max = 0)
    public static double LOWER_SPEED = -0.1;

    @Config(key = "whistle_cooldown", comment = "The cooldown for using the whistle ability.", category = "whistle")
    @RangeConstraint(min = 0, max = Long.MAX_VALUE)
    public static long WHISTLE_COOLDOWN_CONFIG = DragonConstants.WHISTLE_COOLDOWN;

    @Config(
            key = "whistle_check_space",
            comment = "Check if there is enough space to call the dragon before calling it.",
            category = "whistle")
    public static boolean CALL_CHECK_SPACE = true;

    @Config(key = "allow_respawn", comment = "Allow dragons to respawn after being killed.", category = "whistle")
    public static boolean ALLOW_RESPAWN = true;

    @Config(
            key = "respawn_time",
            comment = "Time in seconds for a dragon to respawn after being killed.",
            category = "whistle")
    @RangeConstraint(min = 0, max = Integer.MAX_VALUE)
    public static int RESPAWN_TIME = 60;

    /**
     * How the EntityJoinLevelEvent duplicate-dragon guard reacts when a whistle-bound
     * dragon loads with an entity UUID that does not match the binding's lastSummons
     * entry (community fork, Wave 1; advisor ruling B4).
     */
    public enum DuplicateResolution {
        /** Never cancel loading, never log — the pre-1.9.x disable_duplicate_prevention behavior. */
        OFF,
        /** Never cancel loading; log each detection loudly for operator triage. */
        LOG,
        /** Cancel loading of the mismatched entity (1.9.2 behavior) — with the loud log added. */
        AGGRESSIVE
    }

    @Config(
            key = "duplicate_resolution",
            comment = {
                "How to handle a whistle-bound dragon loading with a mismatched entity id (possible duplicate).",
                "OFF = never cancel loading. LOG = never cancel, log each detection.",
                "AGGRESSIVE = cancel loading of the mismatched entity (may DELETE the original dragon if the binding is stale)."
            },
            category = "whistle")
    // Default flipped AGGRESSIVE -> LOG in Wave 2 (advisor B4 coupling): now that the
    // summon path teleports the real dragon instead of cloning it, join-cancel removal
    // is no longer needed to contain duplication — and with a stale binding AGGRESSIVE
    // deletes the ORIGINAL dragon. LOG never cancels, but keeps operator visibility.
    public static DuplicateResolution DUPLICATE_RESOLUTION = DuplicateResolution.LOG;

    @Config(
            key = "dragon_egg_spawn_chance",
            comment =
                    "Multiplier for dragon egg spawn chances in loot sources. 0 disables spawning, 1 is default rate, 2 doubles the chance.",
            category = "eggs",
            worldRestart = true)
    @RangeConstraint(min = 0.0, max = 100.0)
    public static double DRAGON_EGG_SPAWN_CHANCE = 1.0;

    @Config(
            key = "min_follow_distance",
            comment = "Minimum distance a dragon will maintain when following its owner.",
            category = "behavior")
    @RangeConstraint(min = 1, max = 16)
    public static int MIN_FOLLOW_DISTANCE = 4;

    @Config(
            key = "max_follow_distance",
            comment = "Maximum distance before a dragon will start following its owner.",
            category = "behavior")
    @RangeConstraint(min = 2, max = 64)
    public static int MAX_FOLLOW_DISTANCE = 8;

    @Config(
            key = "summon_walk_max_distance",
            comment = {
                "W8-SUMMON-1a: distance (in blocks) within which a whistled dragon WALKS to its owner",
                "instead of teleporting, in the same dimension. 0.0 (default) means 'use the dragon's",
                "live generic.follow_range attribute' (respects datapack breed overrides via",
                "IDragonBreed#applyAttributes). Set to 64 to restore pre-1.9.2-community.4 behavior.",
                "Never exceeds the pathfinder's own single-computation search radius, regardless of",
                "this value — see ModConstants.DragonConstants.walkSummonMaxDistance."
            },
            category = "whistle")
    @RangeConstraint(min = 0.0, max = 256.0)
    public static double SUMMON_WALK_MAX_DISTANCE = 0.0;

    @Config(
            key = "reclaim_snapshot_clones",
            comment = {
                "Self-heal a proven snapshot-respawn clone: when the whistle dedup check finds two live dragons",
                "sharing one dragonUUID and EXACTLY ONE of them is flagged as a snapshot-respawn clone, discard",
                "ONLY the flagged one (never the original) and re-point the owner's binding at the original.",
                "This is separate from duplicate_resolution above — it never guesses from binding staleness,",
                "only acts on provable clone provenance, and never removes a passenger-carrying dragon."
            },
            category = "whistle")
    public static boolean RECLAIM_SNAPSHOT_CLONES = true;

    @Config(
            key = "persist_hatched_dragons",
            comment = {
                "Mark hatched dragons as persistence-required so they never despawn naturally,",
                "even before being tamed (upstream #64/#90/#124: untamed hatched dragons despawning)."
            },
            category = "behavior")
    public static boolean PERSIST_HATCHED_DRAGONS = true;

    @Config(
            key = "dragon_team_passivity",
            comment = {
                "Tamed dragons will never attack (or continue attacking) a player who is teamed with",
                "the dragon's owner via FTB Teams or Open Parties and Claims, even if that player",
                "accidentally hits the dragon or the owner attacks them first.",
                "Vanilla scoreboard-team allies are always protected regardless of this flag.",
                "Side effect (NOT controlled by this flag, cannot be disabled here): a tamed dragon",
                "will no longer retaliate against, or assist its owner against, another pet owned by",
                "that SAME player while the owner is present, matching vanilla's own owner-delegated",
                "isAlliedTo semantics for tamed animals. This one case applies even with this setting",
                "off and with no team mod installed; only the rare offline/cross-dimension-owner",
                "variant of the same pairing is gated by this flag."
            },
            category = "behavior")
    public static boolean DRAGON_TEAM_PASSIVITY = true;

    @Config(
            key = "log_dragon_tracking_events",
            comment = {
                "W8-SYNC-7: diagnostic for the invisible-dragon-until-relog symptom (#43/#111).",
                "When enabled, logs a DEBUG line every time the server starts or stops tracking a",
                "dragon for a player (PlayerEvent.StartTracking/StopTracking — vanilla server",
                "entity-visibility bookkeeping, not a network packet), with enough detail to",
                "reconstruct the exact add/remove sequence a specific player saw around an incident.",
                "Does NOT by itself diagnose the symptom: every server-side theory for it has already",
                "been refuted, and the surviving hypothesis (client-side occlusion-culling mods",
                "mis-culling the dragon) emits no server-visible signal at all. This diagnostic rules",
                "OUT a server-side tracking anomaly if the symptom recurs; it cannot confirm one.",
                "Default OFF — noisy on a busy server with many dragons and players."
            },
            category = "debug")
    public static boolean LOG_DRAGON_TRACKING_EVENTS = false;

    // Initialize the config
    static {
        MOD_CONFIG_SPEC = ConfigProcessor.processConfig(ServerConfig.class);
    }
}
