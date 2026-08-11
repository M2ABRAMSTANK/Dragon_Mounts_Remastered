package dmr.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dmr.DragonMounts.ModConstants;
import dmr.DragonMounts.server.ai.navigation.DragonPathfindingRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plain JUnit 5 coverage for {@link dmr.DragonMounts.server.ai.navigation.DragonPathfindingRules}
 * — no gametest server needed, since every method that class hosts is a pure function
 * over primitives (see that class's javadoc for the current wave-8 scope).
 *
 * <p>
 * <b>W8-PF13a (commit 1): scaffolding only.</b> {@code DragonPathfindingRules} hosted
 * {@code shouldAllowSwimming} with no production call site and no assertions here,
 * because asserting on it before its wiring commit (W8-PF3) landed would itself have
 * been the kind of vacuous, always-green test this harness exists to prevent.
 *
 * <p>
 * <b>W8-PF3 (this commit): real coverage.</b> Table-driven over every combination of
 * (drownImmune, targetInWater). Per the RED-BASELINE GATE (see {@code
 * .fork-notes/wave8/red-baseline.md}): {@code shouldAllowSwimming}'s two-parameter
 * formula was already written correctly when it was first added in commit 1 (W8-PF13a)
 * — it is a brand-new pure function, not a fix to a previously-wrong one — so there is
 * no meaningful "pre-fix" state in which this exact table could be shown red. That is
 * recorded in red-baseline.md rather than faked; this class exists as a real regression
 * guard for the formula going forward (and as the pure-function complement to the
 * gametest coverage in {@code PathNavigationTests}, which DOES have a genuine red/green
 * pair for the bug W8-PF3 actually fixes — the allowFlying-vs-allowSwimming precedence
 * in {@code DragonNodeEvaluator}).
 */
public class PathfindingRulesTests {

    /**
     * All 4 combinations of (drownImmune, targetInWater) -> expected. Swimming is only
     * ever appropriate for a drown-immune breed whose target is actually water — every
     * other combination must resolve false, including a drown-immune breed pathing to a
     * DRY target (the case the dropped {@code dragonInWater} broadening would have
     * flipped to true; see this class's javadoc and {@code
     * DragonPathfindingRules#shouldAllowSwimming}'s own javadoc for why that broadening
     * is not shipped).
     */
    @ParameterizedTest(name = "drownImmune={0}, targetInWater={1} -> {2}")
    @CsvSource({
        "false, false, false",
        "false, true,  false",
        "true,  false, false",
        "true,  true,  true",
    })
    void shouldAllowSwimming(boolean drownImmune, boolean targetInWater, boolean expected) {
        assertEquals(expected, DragonPathfindingRules.shouldAllowSwimming(drownImmune, targetInWater));
    }

    /** Explicit non-parameterized guard for the one case the design specifically called out as the old bug. */
    @Test
    void drownImmuneBreedWithDryTargetDoesNotSwim() {
        assertEquals(false, DragonPathfindingRules.shouldAllowSwimming(true, false));
    }

    // ---------------------------------------------------------------------------------
    // W8-PF10 (commit 8)
    // ---------------------------------------------------------------------------------

    /**
     * W8-PF10. {@code pathfindSearchRadius} must be a pure function of the LIVE {@code
     * Attributes.FOLLOW_RANGE} value in BOTH directions — not {@code max(attribute, 64)}
     * — per refute-summon.json's corrected summon-01 verdict: a datapack breed override
     * via {@code IDragonBreed.applyAttributes} that RAISES follow_range must widen the
     * search radius, and one that LOWERS it must narrow the radius too, rather than
     * being clamped back up to a fixed floor. Includes a below-default value (10) to
     * prove the lowered-attribute direction specifically, since a max(attr,64)-shaped
     * regression would still pass every at-or-above-32 case in this table.
     */
    @ParameterizedTest(name = "followRangeAttributeValue={0}")
    @ValueSource(doubles = {10.0, 32.0, 64.0, 100.0})
    void pathfindSearchRadiusScalesWithLiveFollowRangeAttributeInBothDirections(double followRangeAttributeValue) {
        assertEquals(
                followRangeAttributeValue * ModConstants.DragonConstants.FOLLOW_RANGE_MULTIPLIER,
                ModConstants.DragonConstants.pathfindSearchRadius(followRangeAttributeValue));
    }

    /**
     * W8-PF10 gate requiredChange #3 ("Verify the visited-node budget... If it cannot be
     * shown deterministically, the 45-block gametest must be restructured (C7)") is
     * discharged by {@code
     * PathNavigationTests#pathfinderReaches45BlockTargetDespiteFollowRangeAttributeOf32},
     * NOT by a unit test here — see that gametest's javadoc. An earlier revision of this
     * class carried a {@code visitedNodeBudgetCoversWidenedSearchRadiusWithComfortableMargin}
     * unit test that compared the visited-node BUDGET (a node count) against the search
     * RADIUS (a distance) scaled by an invented {@code * 8.0} factor; the two quantities
     * are dimensionally unrelated (a 3D flying A* search's node count scales roughly with
     * radius^3, not radius), so that comparison could not actually fail for any budget
     * that was merely non-catastrophic, and every input was a hardcoded literal mirroring
     * production rather than read from it, so it could not detect drift either (changing
     * {@code DragonPathNavigation}'s {@code setMaxVisitedNodesMultiplier(5f)} to {@code 1f}
     * left it green). Deleted rather than patched: the gate explicitly permits discharging
     * requiredChange #3 via the deterministic gametest route, and that gametest already
     * proves the real thing — an actual {@code PathFinder} run against the real budget
     * reaches a real 45-block target — which no reconstruction of the budget/radius math
     * in isolation can substitute for.
     */
    // ---------------------------------------------------------------------------------
    // W8-PF10 fix-round follow-up: the other half of the shared range contract
    // ---------------------------------------------------------------------------------

    /**
     * The cross-area invariant integration-plan.json's verdict names as decisive
     * integration decision #1: "One range contract, defined from the LIVE attribute in
     * both directions... WITH A UNIT-TESTED INVARIANT that the walk threshold never
     * exceeds the pathfinder's single-computation search radius" — {@code
     * walkSummonMaxDistance(cfg, attr) <= pathfindSearchRadius(attr)} for every {@code
     * (configuredWalkMaxDistance, followRangeAttributeValue)} pair, not merely for
     * values one server happens to configure together. A genuine cross product
     * (config values crossed with attribute values), not paired tuples: pairing
     * cfg=200 only with attr=100 (where {@code pathfindSearchRadius(100) == 200}, so
     * the invariant holds at the boundary either way) would let an UNCLAMPED
     * implementation — {@code return configuredWalkMaxDistance > 0 ?
     * configuredWalkMaxDistance : followRangeAttributeValue;}, with no ceiling at all —
     * pass every row "accidentally", the exact failure mode {@code
     * visitedNodeBudgetCoversWidenedSearchRadiusWithComfortableMargin} shipped with
     * (see this class's other javadoc). Crossing cfg=200 against the SMALLEST attr
     * (10, where {@code pathfindSearchRadius(10) == 20}) is what actually discriminates
     * an enforced ceiling from an unenforced one: a datapack that lowers a breed's
     * follow-range attribute must not leave a large operator-configured {@code
     * SUMMON_WALK_MAX_DISTANCE} pointing the walk decision at a distance the
     * pathfinder's own single-computation search cannot reach.
     */
    @ParameterizedTest(name = "configuredWalkMaxDistance={0}, followRangeAttributeValue={1}")
    @CsvSource({
        "0.0,   10.0",
        "0.0,   32.0",
        "0.0,   64.0",
        "0.0,   100.0",
        "32.0,  10.0",
        "32.0,  32.0",
        "32.0,  64.0",
        "32.0,  100.0",
        "64.0,  10.0",
        "64.0,  32.0",
        "64.0,  64.0",
        "64.0,  100.0",
        "200.0, 10.0",
        "200.0, 32.0",
        "200.0, 64.0",
        "200.0, 100.0",
    })
    void walkSummonMaxDistanceNeverExceedsPathfindSearchRadius(
            double configuredWalkMaxDistance, double followRangeAttributeValue) {
        double walkSummonMaxDistance =
                ModConstants.DragonConstants.walkSummonMaxDistance(configuredWalkMaxDistance, followRangeAttributeValue);
        double pathfindSearchRadius = ModConstants.DragonConstants.pathfindSearchRadius(followRangeAttributeValue);

        assertTrue(
                walkSummonMaxDistance <= pathfindSearchRadius,
                "walkSummonMaxDistance(" + configuredWalkMaxDistance + ", " + followRangeAttributeValue + ") = "
                        + walkSummonMaxDistance + " exceeds pathfindSearchRadius(" + followRangeAttributeValue + ") = "
                        + pathfindSearchRadius + " — the cross-area range contract's invariant does not hold");
    }

    /**
     * Explicit non-parameterized guard for {@code walkSummonMaxDistance}'s override
     * semantics, independent of the invariant test above: a positive {@code
     * configuredWalkMaxDistance} below the search-radius ceiling is honored verbatim
     * (not silently replaced by the live attribute), and a non-positive value (the
     * config's documented "derive from follow range" default) falls through to the
     * live attribute exactly like {@code pathfindSearchRadius}'s own live-attribute
     * treatment.
     */
    @Test
    void walkSummonMaxDistanceHonorsPositiveOverrideAndFallsThroughToLiveAttributeOtherwise() {
        assertEquals(20.0, ModConstants.DragonConstants.walkSummonMaxDistance(20.0, 32.0));
        assertEquals(32.0, ModConstants.DragonConstants.walkSummonMaxDistance(0.0, 32.0));
        assertEquals(32.0, ModConstants.DragonConstants.walkSummonMaxDistance(-5.0, 32.0));
    }
}
