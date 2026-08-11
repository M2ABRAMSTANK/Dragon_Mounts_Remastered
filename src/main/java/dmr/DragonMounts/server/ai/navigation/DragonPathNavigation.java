package dmr.DragonMounts.server.ai.navigation;

import dmr.DragonMounts.ModConstants;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags.Fluids;
import org.jetbrains.annotations.Nullable;

public class DragonPathNavigation extends FlyingPathNavigation {
    protected final TameableDragonEntity dragon;

    private int lastPathCreationDelta = 0;
    private static final int TICKS_BETWEEN_PATH_CREATIONS = 5;

    // W8-PF10: a fixed buffer added on top of the straight-line distance to this
    // request's nearest target before clamping into [liveFollowRangeAttribute,
    // pathfindSearchRadius]. PathFinder's maxRange gate (verified against decompiled
    // source: node expansion stops at `node.distanceTo(start) >= maxRange`, insertion
    // requires `node1.walkedDistance < maxRange`) measures WALKED distance, which for
    // any route with turns exceeds the straight-line distance to the target — without
    // this margin a target exactly at the clamped followRange's straight-line distance
    // could still fail to reach if the actual route isn't perfectly direct. Mirrors
    // vanilla's own default regionOffset (8, see PathNavigation.createPath(Set,int)'s
    // `this.createPath(positions, 8, false, distance)`) rather than inventing an
    // unrelated constant.
    private static final double FOLLOW_RANGE_REQUEST_MARGIN = 8.0;

    public DragonPathNavigation(TameableDragonEntity dragon, Level level) {
        super(dragon, level);

        this.dragon = dragon;

        setMaxVisitedNodesMultiplier(5f);
    }

    private DragonNodeEvaluator dragonNodeEvaluator;

    @Override
    protected PathFinder createPathFinder(int pMaxVisitedNodes) {
        this.dragonNodeEvaluator = new DragonNodeEvaluator(mob);
        this.nodeEvaluator = dragonNodeEvaluator;
        // W8-PF4: restores the exact call FlyingPathNavigation.createPathFinder makes
        // (verified against decompiled source) that this override had silently dropped —
        // open doors were being rewritten to BLOCKED instead of treated as passable.
        // Safe here even though swimNodeEvaluator/walkNodeEvaluator are constructed
        // inside `new DragonNodeEvaluator(mob)` above: that constructor fully initializes
        // both delegate fields before returning, so by the time setCanPassDoors forwards
        // to them (via the override above) they already exist.
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, pMaxVisitedNodes);
    }

    @Override
    public boolean canCutCorner(PathType pathType) {
        return super.canCutCorner(pathType) || pathType == PathType.WATER;
    }

    @Override
    protected Vec3 getTempMobPos() {
        return mob.position().subtract(0.0D, 0, 0.0D);
    }

    @Override
    protected double getGroundY(Vec3 p_217794_) {
        return dragonNodeEvaluator.allowSwimming ? p_217794_.y : super.getGroundY(p_217794_);
    }

    @Override
    public void tick() {
        super.tick();

        lastPathCreationDelta++;
    }

    // W8-PF10: overrides PathNavigation's protected 4-arg createPath (verified against
    // decompiled source: it normally derives followRange purely from the LIVE
    // Attributes.FOLLOW_RANGE value and delegates to the 5-arg overload) so followRange
    // is scaled to what THIS specific request actually needs, clamped between the live
    // FOLLOW_RANGE attribute (today's floor — a short request never shrinks below what
    // it already gets) and ModConstants.DragonConstants.pathfindSearchRadius (today's
    // ceiling — the ONE contract this navigator and DragonWhistleHandler's summon
    // walk/teleport decision both consume, per integration-plan.json conflict (a)).
    //
    // Deliberately scales PER REQUEST rather than pinning followRange to the ceiling
    // unconditionally: followRange also sizes the PathNavigationRegion snapshot at
    // ±(followRange + regionOffset) (verified: PathNavigation.createPath(Set,int,
    // boolean,int,float)'s `i = (int)(followRange + regionOffset)`), so pinning 64
    // globally would move EVERY pathfind — including a 5-block one — from vanilla's
    // ~±40 (~6x6 chunk) snapshot to ~±72 (~10x10 chunk), for every dragon, on every
    // pathfind, on a live server. A request whose nearest target is already within the
    // live attribute's range is unaffected (clamp floors at attributeValue); only a
    // genuinely distant target pays for the wider snapshot.
    //
    // Does NOT affect maxVisitedNodes: that budget is fixed once at construction
    // (PathNavigation's constructor: `Mth.floor(mob.getAttributeValue(FOLLOW_RANGE) *
    // 16.0)`, scaled per findPath call by this class's own setMaxVisitedNodesMultiplier
    // (5f)) and is untouched by this override — see
    // PathNavigationTests#pathfinderReaches45BlockTargetDespiteFollowRangeAttributeOf32
    // for the deterministic, real-PathFinder-run proof that the budget still reaches a
    // widened-radius target at today's constants (a prior unit test attempted to pin
    // this via reconstructed budget/radius arithmetic instead; it compared a node COUNT
    // against a distance scaled by an invented factor and was deleted as dimensionally
    // meaningless — see PathfindingRulesTests's javadoc at the same anchor for why).
    @Override
    protected @Nullable Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
        double attributeValue = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        double searchRadius = ModConstants.DragonConstants.pathfindSearchRadius(attributeValue);

        double distanceToNearestTarget = targets.stream()
                .mapToDouble(target -> mob.position().distanceTo(Vec3.atCenterOf(target)))
                .min()
                .orElse(0.0);

        float followRange =
                (float) Mth.clamp(distanceToNearestTarget + FOLLOW_RANGE_REQUEST_MARGIN, attributeValue, searchRadius);

        return this.createPath(targets, regionOffset, offsetUpward, accuracy, followRange);
    }

    @Override
    public @Nullable Path createPath(BlockPos pos, int accuracy) {
        // W8-PF2: the throttle must never masquerade as "target unreachable". Vanilla
        // callers treat a null return as a genuine failure — MoveToTargetSink.tryComputePath
        // erases WALK_TARGET on null, and PathNavigation.recomputePath does
        // `this.path = null; this.path = this.createPath(...)`, so a throttled null
        // PERMANENTLY destroys the live in-flight path (refute-pathfind.json
        // missedBugs#4). When throttled AND a live, non-done path already targets the
        // SAME destination as this request (within `accuracy`), hand that path back
        // unchanged instead of lying. Note `this.path` here is PathNavigation's own
        // "currently being followed" field (set by moveTo/recomputePath), not merely the
        // return value of the last createPath call — matching vanilla's own same-target
        // reuse guard in createPath(Set,...).
        //
        // A DIFFERENT destination requested inside the throttle window is deliberately
        // NOT served the stale path — this navigation is shared by
        // StayCloseToTarget/MoveToTargetSink/attack targeting/RandomStroll/recomputePath,
        // so serving the wrong target would be a new bug worse than the one being fixed.
        // It falls through to the branch below and computes fresh, exactly like a
        // throttled request with no usable cached path at all (first-ever call, or a
        // path just nulled by recomputePath's eager reset / stuck-detection).
        if (lastPathCreationDelta < TICKS_BETWEEN_PATH_CREATIONS) {
            BlockPos currentTarget = getTargetPos();
            if (this.path != null
                    && !this.path.isDone()
                    && currentTarget != null
                    && currentTarget.distSqr(pos) <= (long) accuracy * (long) accuracy) {
                return this.path;
            }
        }

        // Reset on EVERY branch that reaches here — i.e. both "window elapsed" AND
        // "throttled but nothing usable to fall back on" — not only the window-elapsed
        // case. Resetting only on window-elapsed (the original sketch's bug) means an
        // unreachable or freshly-retargeted request never re-arms the throttle, so a
        // dragon with no reachable path would run a full A* EVERY tick instead of
        // 1-in-5 — a live-server regression precisely where A* is most expensive.
        lastPathCreationDelta = 0;

        // W8-PF3: derive drown-immunity through the existing canDrownInFluidType helper
        // (DragonMovementComponent) instead of re-implementing the breed-immunity check
        // at a second call site, and drive the actual predicate through
        // DragonPathfindingRules.shouldAllowSwimming so it stays unit-testable in
        // isolation. The design's dragon.isInWater() broadening is deliberately NOT
        // applied here (see DragonPathfindingRules.shouldAllowSwimming's javadoc) — it
        // was dropped at integration because it would route ALL non-flying pathing
        // through swimNodeEvaluator and make the walk delegate's canFloat propagation
        // (W8-PF4) unreachable for a dragon standing in water, the exact scenario it was
        // added for.
        boolean drownImmune =
                !dragon.canDrownInFluidType(net.minecraft.world.level.material.Fluids.WATER.getFluidType());
        boolean targetInWater = dragon.level.getFluidState(pos).is(Fluids.WATER);
        dragonNodeEvaluator.allowSwimming = DragonPathfindingRules.shouldAllowSwimming(drownImmune, targetInWater);

        // If the dragon's already flying, we create a flight path right away.
        if (dragon.isFlying()) {
            return createPathWithFlyingAllowed(pos, accuracy);
        }

        dragonNodeEvaluator.allowFlying = false;

        // Otherwise, let's try and get a path to the target position by walking or swimming.
        Path path = super.createPath(pos, accuracy);
        if (path != null && path.canReach() && path.getNodeCount() > 1) {
            return path;
        }

        // If there's no reason for the dragon to fly, settle for the walking/swimming path.
        var dif = mob.blockPosition().distManhattan(pos);
        var jumpHeight = Math.max(1.125f, mob.maxUpStep());
        if (Mth.abs(dif) < jumpHeight) {
            return path;
        }

        // If walking or swimming doesn't work, let's try to fly there.
        return createPathWithFlyingAllowed(pos, accuracy);
    }

    // W8-PF1: streamlinePath deleted entirely. It scanned the WHOLE computed path and
    // advanced nextNodeIndex to whichever node was closest to (or farther from, per its
    // own inverted-looking OR condition) the target, which for a normal multi-waypoint
    // route collapsed nextNodeIndex almost to the LAST node — the dragon was told to
    // beeline for the terminal node from tick one instead of following the intermediate
    // waypoints A* actually computed to route around obstacles. The mutation survives
    // into the live path (PathNavigation.moveTo only swaps paths via a coordinate-only
    // `sameAs` check, so a collapsed nextNodeIndex is never corrected), so this was the
    // literal cause of flying dragons beelining into obstacles and stalling instead of
    // routing around them. The motivating scenario ("a mid-flight repath that briefly
    // backtracks") is a real, separate concern, but this unbounded whole-path scan is
    // not a safe way to address it; a future fix should be a BOUNDED prefix trim
    // restricted to `index < path.getNextNodeIndex()` (nodes strictly already passed),
    // never advancing past the current waypoint.
    private Path createPathWithFlyingAllowed(BlockPos pos, int accuracy) {
        dragonNodeEvaluator.allowFlying = true;
        return super.createPath(pos, accuracy);
    }

    @Override
    protected boolean canUpdatePath() {
        return true;
    }

    // W8-PF6: restores vanilla FlyingPathNavigation.canMoveDirectly's unconditional
    // line-of-sight corner-cut for the flying case, gated on dragon.isFlying() rather
    // than the previous unconditional-false-for-flight behavior (this override
    // previously only ever returned true for the swim case, disabling corner-cutting
    // entirely for flight). Consumed by PathNavigation.followThePath's
    // `canCutCorner(...) && shouldTargetNextNodeInDirection(...)` OR-arm, which lets a
    // 2.75-wide dragon advance past waypoints it has clear line-of-sight to instead of
    // being forced to physically arrive within maxDistanceToWaypoint (bbWidth/2 = 1.375
    // blocks) of every single node — directly depends on W8-PF1 above: with the beeline
    // bug in place the dragon rarely had real intermediate waypoints to corner-cut
    // between in the first place.
    //
    // Keyed on dragon.isFlying() (the LIVE flight state) rather than
    // dragonNodeEvaluator.allowFlying: that field reflects the mode of the LAST
    // createPath() call, not necessarily the path currently being followed --
    // DragonPathNavigation is shared by StayCloseToTarget/MoveToTargetSink/attack
    // targeting/RandomStroll/recomputePath, so a walk-mode repath issued by one consumer
    // while a flight path computed moments earlier is still being followed would read
    // allowFlying=false and silently disable corner-cutting for a path that IS a flight
    // path. dragon.isFlying() is not similarly aliased by unrelated repaths. (This same
    // staleness class already affects getGroundY/isStableDestination below, which read
    // the same mutable flags for a different purpose; parked, not widened here.)
    @Override
    protected boolean canMoveDirectly(Vec3 p_217796_, Vec3 p_217797_) {
        if (dragon.isFlying()) {
            return isClearForMovementBetween(this.mob, p_217796_, p_217797_, true);
        }

        return dragonNodeEvaluator.allowSwimming
                && this.mob.isInLiquid()
                && isClearForMovementBetween(this.mob, p_217796_, p_217797_, true);
    }

    public boolean isStableDestination(BlockPos pPos) {
        if (dragonNodeEvaluator.allowFlying) {
            return this.level.getBlockState(pPos).entityCanStandOn(this.level, pPos, this.mob);
        }

        if (dragonNodeEvaluator.allowSwimming) {
            return !this.level.getBlockState(pPos.below()).isAir();
        }

        BlockPos blockpos = pPos.below();
        return this.level.getBlockState(blockpos).isSolidRender(this.level, blockpos);
    }
}
