package dmr.DragonMounts.server.ai.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.*;
import org.jetbrains.annotations.Nullable;

public class DragonNodeEvaluator extends FlyNodeEvaluator {

    private final Mob mob;
    public boolean allowFlying = false;
    public boolean allowSwimming = false;

    private final SwimNodeEvaluator swimNodeEvaluator;
    private final WalkNodeEvaluator walkNodeEvaluator;

    public DragonNodeEvaluator(Mob mob) {
        this.mob = mob;

        this.swimNodeEvaluator = new SwimNodeEvaluator(true);
        this.walkNodeEvaluator = new WalkNodeEvaluator();
    }

    @Override
    public void prepare(PathNavigationRegion level, Mob mob) {
        super.prepare(level, mob);
        this.swimNodeEvaluator.prepare(level, mob);
        this.walkNodeEvaluator.prepare(level, mob);
    }

    // W8-PF9: PathFinder.findPath calls prepare() and done() exactly once each; prepare()
    // above already forwards to both delegates, but done() previously only ran the
    // composite's own inherited FlyNodeEvaluator.done(), leaving swimNodeEvaluator and
    // walkNodeEvaluator's currentContext/mob (and, for walkNodeEvaluator, its
    // pathTypesByPosCacheByMob/collisionCache) pinned between pathfinds. Mirrors the
    // prepare() forwarding pattern above. Safe ordering: NodeEvaluator.done() only nulls
    // the RECEIVER's own currentContext/mob, and each delegate holds its own copies, so
    // there is no shared-state hazard between the three done() calls.
    //
    // Deliberately NOT fixed here (parked, matches design's own honest note): this
    // restores 2-starts/2-dones SYMMETRY, not a single-count. walkNodeEvaluator.done()
    // still calls mob.onPathfindingDone() a second time (matching walkNodeEvaluator's
    // own prepare() already calling mob.onPathfindingStart() a second time) — removing
    // that double-count would mean not delegating prepare() to walkNodeEvaluator at all,
    // a larger structural change out of scope for this lifecycle-balance fix.
    @Override
    public void done() {
        super.done();
        this.swimNodeEvaluator.done();
        this.walkNodeEvaluator.done();
    }

    // W8-PF4: forward every NodeEvaluator capability setter to both delegates (and to
    // super, so the allowFlying=true code paths that call super.* directly also stay in
    // sync) — mirrors prepare()'s forwarding pattern. Before this fix these setters only
    // ever reached the composite's own inherited FlyNodeEvaluator fields, so e.g.
    // DragonMovementComponent.createNavigation's setCanFloat(true) call never reached
    // swimNodeEvaluator/walkNodeEvaluator at all.
    @Override
    public void setCanFloat(boolean canFloat) {
        super.setCanFloat(canFloat);
        this.swimNodeEvaluator.setCanFloat(canFloat);
        this.walkNodeEvaluator.setCanFloat(canFloat);
    }

    @Override
    public void setCanPassDoors(boolean canPassDoors) {
        super.setCanPassDoors(canPassDoors);
        this.swimNodeEvaluator.setCanPassDoors(canPassDoors);
        this.walkNodeEvaluator.setCanPassDoors(canPassDoors);
    }

    @Override
    public void setCanOpenDoors(boolean canOpenDoors) {
        super.setCanOpenDoors(canOpenDoors);
        this.swimNodeEvaluator.setCanOpenDoors(canOpenDoors);
        this.walkNodeEvaluator.setCanOpenDoors(canOpenDoors);
    }

    @Override
    public void setCanWalkOverFences(boolean canWalkOverFences) {
        super.setCanWalkOverFences(canWalkOverFences);
        this.swimNodeEvaluator.setCanWalkOverFences(canWalkOverFences);
        this.walkNodeEvaluator.setCanWalkOverFences(canWalkOverFences);
    }

    // W8-PF4 triage (gate-design-pathfind.json "missing" list item 3): getStart() had no
    // allowFlying arm at all — only an allowSwimming-first branch that, when true, keyed
    // entirely off mob.isInWater() with no consideration of whether this getStart() call
    // is for a flying retry. A flying, drown-immune dragon starting its search from
    // inside/near water would incorrectly get swimNodeEvaluator's start node instead of
    // the flight-appropriate one. allowFlying now wins first, matching the precedence
    // rule the wave establishes elsewhere (findAcceptedNode, getPathType, getNeighbors).
    @Override
    public Node getStart() {
        if (allowFlying) {
            return super.getStart();
        }

        if (allowSwimming) {
            return !this.mob.isInWater() ? super.getStart() : swimNodeEvaluator.getStart();
        }

        return super.getStart();
    }

    @Override
    public Target getTarget(double x, double y, double z) {
        if (allowFlying) {
            return super.getTarget(x, y, z);
        }

        if (this.allowSwimming) {
            return swimNodeEvaluator.getTarget(x, y, z);
        }

        return walkNodeEvaluator.getTarget(x, y, z);
    }

    @Override
    public boolean canStartAt(BlockPos pos) {
        if (allowFlying) {
            return super.canStartAt(pos);
        }

        if (allowSwimming) {
            return true;
        }

        PathType pathtype = getCachedPathType(pos.getX(), pos.getY(), pos.getZ());
        return pathtype != PathType.OPEN && this.mob.getPathfindingMalus(pathtype) >= 0.0F;
    }

    @Override
    protected boolean isAmphibious() {
        return !mob.canDrownInFluidType(Fluids.WATER.getFluidType()) || allowSwimming;
    }

    @Override
    public int getNeighbors(Node[] outputArray, Node p_node) {
        if (allowFlying) {
            return super.getNeighbors(outputArray, p_node);
        }

        if (this.allowSwimming) {
            return swimNodeEvaluator.getNeighbors(outputArray, p_node);
        }

        return walkNodeEvaluator.getNeighbors(outputArray, p_node);
    }

    @Override
    protected @Nullable Node findAcceptedNode(int x, int y, int z) {
        if (this.allowSwimming) {
            return swimNodeEvaluator.findAcceptedNode(x, y, z);
        }

        return super.findAcceptedNode(x, y, z);
    }

    // W8-PF4 triage (gate-design-pathfind.json "missing" list item 2): this override
    // delegated unconditionally to walkNodeEvaluator, with no allowFlying arm — the same
    // precedence gap as getStart() above. In today's call graph this override is only
    // reachable via WalkNodeEvaluator.getNeighbors' internal `this.findAcceptedNode(...)`
    // calls, which (because Java protected/package dispatch resolves against the DECLARED
    // field type) run against the walkNodeEvaluator delegate INSTANCE directly rather than
    // through this composite override — so today it is inert. Fixed anyway for
    // correctness/consistency with the rest of this class's precedence rule, and so it is
    // not silently wrong if a future change ever routes flying neighbor generation through
    // the 7-arg walk-style expansion.
    @Override
    public @Nullable Node findAcceptedNode(
            int x,
            int y,
            int z,
            int verticalDeltaLimit,
            double nodeFloorLevel,
            Direction direction,
            PathType pathType) {
        if (allowFlying) {
            return super.findAcceptedNode(x, y, z, verticalDeltaLimit, nodeFloorLevel, direction, pathType);
        }

        return walkNodeEvaluator.findAcceptedNode(x, y, z, verticalDeltaLimit, nodeFloorLevel, direction, pathType);
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        if (allowSwimming) {
            return swimNodeEvaluator.getPathType(context, x, y, z);
        } else if (allowFlying) {
            return super.getPathType(context, x, y, z);
        } else {
            return walkNodeEvaluator.getPathType(context, x, y, z);
        }
    }
}
