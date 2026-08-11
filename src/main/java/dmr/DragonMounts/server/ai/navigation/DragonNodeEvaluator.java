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

    // W8-PF3: allowFlying now wins over allowSwimming here too, matching getNeighbors'
    // existing precedence. Before this fix, a flying retry (allowFlying=true) whose
    // target/breed also satisfied allowSwimming got every FlyNodeEvaluator neighbour
    // graded through swimNodeEvaluator.findAcceptedNode instead of the flight rules —
    // SwimNodeEvaluator only ever accepts WATER/BREACH-classified cells and adds a
    // further malus penalty on top, so a flying dragon retrying toward (or over) water
    // had its open-air neighbours rejected or overpriced for no reason connected to
    // flight itself.
    @Override
    protected @Nullable Node findAcceptedNode(int x, int y, int z) {
        if (this.allowSwimming && !this.allowFlying) {
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

    // W8-PF3: same precedence bug as findAcceptedNode(int,int,int) above, at the
    // single-cell classification level this time — reorder so allowFlying is checked
    // first, matching getNeighbors' existing precedence and findAcceptedNode's fix
    // above. Pre-fix, this method's allowSwimming-first check meant a flying retry
    // toward a water target had every cell (including the water target cell itself,
    // which SwimNodeEvaluator.getPathTypeOfMob's box-sampling can resolve to BLOCKED)
    // graded under swim rules instead of flight rules.
    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        if (allowFlying) {
            return super.getPathType(context, x, y, z);
        } else if (allowSwimming) {
            return swimNodeEvaluator.getPathType(context, x, y, z);
        } else {
            return walkNodeEvaluator.getPathType(context, x, y, z);
        }
    }

    // W8-PF8: cure the 3x3x3 box-sampling root cause for this wide flying dragon,
    // rather than relaxing the LEAVES malus table (the original design's approach,
    // rejected by the gate: a mob-level malus override applies to canStartAt and to the
    // WALK evaluator too, so it would also route a WALKING dragon through solid leaf
    // blocks — leaves ARE full-collision cubes in 1.21.1, unlike the parked FENCE case).
    //
    // NodeEvaluator.prepare sets entityWidth=entityHeight=entityDepth=Mth.floor(2.75+1)
    // = 3 for this dragon (see WalkNodeEvaluator.getPathTypeWithinMobBB), so
    // WalkNodeEvaluator.getPathTypeOfMob (inherited unmodified here via
    // FlyNodeEvaluator, which does not override it) rejects a node OUTRIGHT — malus
    // -1.0F, BLOCKED — the moment its 3x3x3 sampling box merely brushes a leaf block
    // anywhere, even when the node's own centre is open air with a clear leaf-free
    // flight line straight through it. That is the literal cause of "no path found at
    // all" near forest canopy this fix targets. Vanilla already has the right
    // refinement for this shape of problem — getPathTypeOfMob's own small-mob tail
    // (`entityWidth <= 1 && ... getPathType(...) == OPEN ? OPEN : pathtype`) — but
    // explicitly excludes any mob wider than 1 block. This override extends that same
    // reclassification to the wide flying dragon, scoped to LEAVES only.
    //
    // Scoping: this override lives on DragonNodeEvaluator itself (which IS-A
    // FlyNodeEvaluator), so it is only reachable through `this.getPathTypeOfMob(...)`
    // calls made by the inherited FlyNodeEvaluator/WalkNodeEvaluator machinery — i.e.
    // the FLY evaluator path (findAcceptedNode/getCachedPathType when allowFlying is
    // true, via super.findAcceptedNode/super.getPathType above). walkNodeEvaluator is a
    // wholly separate NodeEvaluator instance whose own getPathTypeOfMob is never
    // overridden, so a walking dragon is completely unaffected. canStartAt's walk-mode
    // fallback (neither allowFlying nor allowSwimming) does call this override via its
    // own inherited getCachedPathType, but is provably unaffected: its formula
    // `pathtype != OPEN && malus(pathtype) >= 0` evaluates to false either way for a
    // node this override touches (pre-fix pathtype=LEAVES gives a false via the second
    // clause since LEAVES' malus stays untouched and negative; post-fix pathtype=OPEN
    // gives false via the first clause) — see W8-PF8's commit body for the full
    // derivation. getPathfindingMalus itself is deliberately untouched.
    //
    // A node whose OWN centre IS a leaf block is untouched by this override (the
    // `this.getPathType(context, x, y, z)` guard below only fires when the centre
    // itself is OPEN) and keeps LEAVES' default negative malus — BLOCKED — so the
    // dragon is never routed through solid canopy interior, only past its edges.
    @Override
    public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
        PathType pathtype = super.getPathTypeOfMob(context, x, y, z, mob);
        if (pathtype == PathType.LEAVES && this.getPathType(context, x, y, z) == PathType.OPEN) {
            return PathType.OPEN;
        }
        return pathtype;
    }
}
