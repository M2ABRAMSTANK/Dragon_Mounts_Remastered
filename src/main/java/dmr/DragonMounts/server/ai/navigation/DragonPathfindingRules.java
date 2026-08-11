package dmr.DragonMounts.server.ai.navigation;

/**
 * Pure, {@code Mob}/{@code Level}-independent pathfinding decision predicates, extracted
 * out of {@link DragonPathNavigation}/{@link DragonNodeEvaluator} so they can be covered
 * by plain JUnit tests without booting a gametest server.
 *
 * <p>
 * <b>Wave 8 scope (W8-PF13a, amended):</b> this class hosts ONLY {@link
 * #shouldAllowSwimming}. The original design (design-pathfind.json W8-PF13) also
 * proposed hosting {@code shouldSettleForWalkPath} here for W8-PF5, but W8-PF5 was
 * REJECTED at the design-gate stage (gate-design-pathfind.json: activating the dead
 * "settle for walk path" guard on a vertical-delta comparison alone is a regression — a
 * dragon separated from its target by a same-elevation obstruction would stop
 * escalating to a flying retry the moment the walk attempt merely fails to reach) and is
 * parked. Its pure-function seam does not ship; see integration-plan.json shipList entry
 * "W8-PF13a".
 */
public final class DragonPathfindingRules {

    private DragonPathfindingRules() {}

    /**
     * Whether the pathfinder should evaluate nodes through {@code SwimNodeEvaluator}
     * instead of the walk/fly evaluators.
     *
     * <p>
     * <b>DEVIATION FROM THE ORIGINAL W8-PF3 DESIGN:</b> the original design's version of
     * this predicate took a third parameter, {@code dragonInWater}, broadening the
     * trigger so a drown-immune dragon currently submerged (regardless of its target)
     * would also get swim-mode evaluation. The wave-8 integration plan's AMENDED W8-PF3
     * spec explicitly drops that broadening: "the dragon.isInWater() broadening is
     * DROPPED (it would route all non-flying pathing through swimNodeEvaluator and make
     * PF4's walk delegate canFloat propagation unreachable for the exact scenario it was
     * added for)". This method therefore reproduces TODAY'S production formula (breed
     * drown-immunity AND the target block being water) as a pure, testable function.
     *
     * <p>
     * <b>Wired in by W8-PF3</b> ({@link DragonPathNavigation#createPath}), alongside the
     * allowFlying-vs-allowSwimming PRECEDENCE fix in {@code DragonNodeEvaluator}'s
     * delegating methods — the two land in the same commit because the precedence bug is
     * only observable when both flags can be true simultaneously, i.e. exactly when this
     * predicate returns {@code true} for a dragon that is also flying.
     *
     * @param drownImmune whether the dragon's breed is immune to drowning. The
     *     production call site derives this via {@code
     *     !dragon.canDrownInFluidType(Fluids.WATER.getFluidType())} (that method returns
     *     {@code true} when the dragon CAN drown, i.e. is NOT immune) rather than
     *     re-implementing {@code breed.getImmunities().contains("drown")} at a second
     *     call site — this method's boolean parameter is unaffected by which helper
     *     computed it.
     * @param targetInWater whether the pathfind target block is water.
     * @return {@code true} if swim-mode node evaluation should be used.
     */
    public static boolean shouldAllowSwimming(boolean drownImmune, boolean targetInWater) {
        return drownImmune && targetInWater;
    }
}
