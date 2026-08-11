package dmr.DragonMounts.server.ai.navigation;

import dmr.DragonMounts.server.entity.TameableDragonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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

    @Override
    public @Nullable Path createPath(BlockPos pos, int accuracy) {
        if (lastPathCreationDelta < TICKS_BETWEEN_PATH_CREATIONS) {
            return null;
        }

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
