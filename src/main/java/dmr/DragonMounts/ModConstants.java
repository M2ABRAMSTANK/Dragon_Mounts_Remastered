package dmr.DragonMounts;

public class ModConstants {

    public static final String VARIANT_DIVIDER = "$";

    public static class DragonConstants {

        // Multiplier for BASE_FOLLOW_RANGE when determining if a dragon should walk or
        // teleport to
        // player
        public static final double FOLLOW_RANGE_MULTIPLIER = 2.0;

        /**
         * W8-PF10: the ONE distance both the summon walk/teleport decision ({@code
         * DragonWhistleHandler.summonExistingDragon}, repointed in a later wave-8 commit)
         * and the pathfinder's actual search ceiling ({@code DragonPathNavigation}) must
         * agree on. Do not let either site recompute {@code followRangeAttributeValue *
         * FOLLOW_RANGE_MULTIPLIER} inline again — reference this method from both.
         *
         * <p>
         * Deliberately a pure function over the LIVE {@code Attributes.FOLLOW_RANGE}
         * value (not a compile-time constant, and not {@code max(attribute, 64)}) so a
         * datapack that raises OR lowers a breed's follow range via {@code
         * IDragonBreed.applyAttributes} is respected in both directions — see
         * refute-summon.json's corrected summon-01 verdict, which is what ruled out
         * clamping to a fixed floor. Takes the attribute VALUE rather than a {@code Mob}
         * so it stays plain-JUnit-testable in isolation, matching the {@code
         * DragonPathfindingRules.shouldAllowSwimming} precedent — {@code Mob}/{@code
         * LivingEntity} cannot be bootstrapped under {@code ./gradlew test} in this repo.
         */
        public static double pathfindSearchRadius(double followRangeAttributeValue) {
            return followRangeAttributeValue * FOLLOW_RANGE_MULTIPLIER;
        }

        /**
         * W8-PF10: the OTHER half of the shared range contract described on {@link
         * #pathfindSearchRadius} — the summon walk/teleport decision's threshold ({@code
         * DragonWhistleHandler.summonExistingDragon}, repointed in a later wave-8 commit).
         * Not consumed by production code until {@code ServerConfig.SUMMON_WALK_MAX_DISTANCE}
         * is wired through in that later commit, but it lands here now — alongside {@code
         * pathfindSearchRadius} — so the cross-area invariant both areas must honor
         * (integration-plan.json's decisive integration decision #1: "the walk threshold
         * never exceeds the pathfinder's single-computation search radius") is pinned by a
         * unit test from the moment either half exists, rather than shipping with only one
         * half owned.
         *
         * <p>
         * {@code configuredWalkMaxDistance > 0} acts as an explicit operator override (the
         * config's documented "0 = derive from follow range" default); otherwise the live
         * {@code followRangeAttributeValue} is used directly, mirroring {@code
         * pathfindSearchRadius}'s own live-attribute-in-both-directions treatment. The
         * result is additionally clamped to never exceed {@link #pathfindSearchRadius} for
         * the SAME attribute value: an operator-configured override is a distance a dragon
         * should be willing to WALK, not a promise that the pathfinder's own single-search
         * ceiling was raised to match, and the plan's own wording ("for every config value")
         * requires the invariant to hold universally, not merely for the specific values one
         * server happens to configure. Without this clamp a large {@code
         * SUMMON_WALK_MAX_DISTANCE} paired with a datapack-lowered follow-range attribute
         * would summon-walk the dragon toward a target the pathfinder itself cannot reach in
         * a single computation — silently degrading to a non-reaching best-effort path
         * instead of the clean teleport fallback the walk/teleport decision exists to choose
         * between. Takes both values as {@code double} for the same plain-JUnit-testability
         * reason as {@link #pathfindSearchRadius}.
         */
        public static double walkSummonMaxDistance(double configuredWalkMaxDistance, double followRangeAttributeValue) {
            double requested = configuredWalkMaxDistance > 0 ? configuredWalkMaxDistance : followRangeAttributeValue;
            return Math.min(requested, pathfindSearchRadius(followRangeAttributeValue));
        }

        // Sound pitch values for dragon whistle
        public static final float WHISTLE_BASE_PITCH = 1.4f;
        public static final float WHISTLE_PITCH_DIVISOR = 3.0f;

        // Search radius for finding dragons near player
        public static final double DRAGON_SEARCH_RADIUS = 100.0;

        // Dragon state packet values
        public static final int DRAGON_STATE_FOLLOW = 1;

        // Minimum dragon health when summoned
        public static final float MIN_DRAGON_HEALTH = 1.0f;
    }

    public static class NBTConstants {

        public static final String BREED = "breed";
        public static final String SADDLED = "saddle";
        public static final String DRAGON_UUID = "dragonUUID";
        public static final String WANDERING_POS = "wanderingPosition";
        public static final String CHEST = "chest";
        public static final String VARIANT = "variant";
        public static final String ORDERED_TO_SIT = "OrderedToSit";
        public static final String WAS_HATCHED = "wasHatched";
        /** Wave 5, Fix B4: snapshot-respawn clone provenance flag. */
        public static final String RESPAWNED_FROM_SNAPSHOT = "respawnedFromSnapshot";
        /** Wave 5 review Blocker 1(b): game time the clone-provenance flag was stamped at. */
        public static final String SNAPSHOT_MINT_GAME_TIME = "snapshotMintGameTime";
    }
}
