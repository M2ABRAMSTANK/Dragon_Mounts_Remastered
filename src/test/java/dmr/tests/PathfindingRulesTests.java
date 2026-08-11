package dmr.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dmr.DragonMounts.server.ai.navigation.DragonPathfindingRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}
