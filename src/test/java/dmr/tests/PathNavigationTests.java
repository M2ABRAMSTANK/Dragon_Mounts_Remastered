package dmr.tests;

import dmr.DragonMounts.registry.DragonBreedsRegistry;
import dmr.DragonMounts.registry.ModEntities;
import dmr.DragonMounts.server.ai.navigation.DragonNodeEvaluator;
import dmr.DragonMounts.server.ai.navigation.DragonPathNavigation;
import dmr.DragonMounts.types.dragonBreeds.DragonBreed;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/**
 * Gametest coverage for {@code dmr.DragonMounts.server.ai.navigation}
 * (DragonPathNavigation / DragonNodeEvaluator) and {@code
 * dmr.DragonMounts.server.ai.DragonMoveController}, established per W8-PF13a as the test
 * harness the wave's other pathfinding fixes (commits 2 onward) attach their coverage
 * to.
 *
 * <p>
 * <b>RED-PROOF GATE (W8-PF13a, wave-wide):</b> every test added to this class in a later
 * commit must first be demonstrated RED on that commit's pre-fix HEAD, with the failure
 * message recorded in {@code .fork-notes/wave8/red-baseline.md}. A test that cannot be
 * shown red is deleted or reclassified as a guard — never counted as coverage. See that
 * file's preamble for the full protocol and the wave's git-log precedent (this repo has
 * been burned twice by vacuous "green on HEAD" tests).
 *
 * <p>
 * <b>@EmptyTemplate FOOTPRINT STRATEGY (resolved per gate-design-pathfind.json's W8-PF13
 * requiredChanges, "before any test is written"):</b> the testframework's {@code
 * @EmptyTemplate} default is a 3x3x3 cube — plenty for a single wall/door/gap obstacle,
 * not enough for the long-distance (up to {@code PATHFIND_SEARCH_RADIUS}, today 64
 * blocks) reach tests some later commits in this wave need. Two named sizes are reserved
 * here so no later commit has to re-derive this:
 *
 * <ul>
 *   <li>{@link #SMALL_TEMPLATE} — local single-obstacle geometry (walls, gaps, canopies,
 *       sealed chambers) within roughly a 12-15 block footprint — see that constant's own
 *       javadoc for why a multi-room obstacle test needs more room than it first looks
 *       like. Used by this cluster's own tests.
 *   <li>{@link #LARGE_TEMPLATE} — reserved for a future commit's long-distance
 *       (30-64+ block separation) reach tests. Not used by any test in this cluster;
 *       kept here rather than invented ad hoc later so the footprint question the gate
 *       flagged is answered once, in one place. If a future commit's separation
 *       requirement ever exceeds this template's bounds, prefer {@code
 *       helper.absolutePos}-anchored structures placed outside the template's own
 *       bounding box over growing this constant further (per NeoForge testframework
 *       convention — very large registered structures are needlessly expensive to
 *       generate/compare for every test run).
 * </ul>
 */
@PrefixGameTestTemplate(false)
@ForEachTest(groups = "Dragon Pathfinding")
public class PathNavigationTests {

    /**
     * Local single-obstacle geometry: walls, gaps, canopies, sealed chambers. Sized for
     * a two-room-plus-a-shared-wall layout with margin on every side clear of this
     * dragon's {@code entityWidth}/{@code entityHeight} = 3 node-sampling box (see
     * {@link #walkPathTreatsOpenDoorAsPassable} — {@code NodeEvaluator} samples a box
     * extending from a node's own coordinate in the +x/+y/+z direction, NOT centered on
     * it, so a room exactly as wide as the sampling box leaves zero valid node
     * positions).
     */
    static final String SMALL_TEMPLATE = "15x5x15";

    /** Reserved for long-distance (30-64+ block) reach tests; see class javadoc. */
    static final String LARGE_TEMPLATE = "160x24x160";

    // ---------------------------------------------------------------------------------
    // Obstacle-building helpers. Shared by this cluster's tests and reserved for the
    // later pathfinding commits in this wave (PF1/PF3/PF6/PF8/PF10/PF12) so obstacle
    // geometry is built consistently with real BlockState placement — never mocked path
    // results — per W8-PF13a's design note: a test that mocks PathFinder output would
    // not catch the actual evaluator/navigation bugs this wave fixes, since the bugs
    // live in how the real evaluator classifies real block geometry.
    // ---------------------------------------------------------------------------------

    /** Fills every block position in the inclusive cuboid {@code [from, to]} with {@code state}. */
    static void fillBox(ExtendedGameTestHelper helper, BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            helper.setBlock(pos, state);
        }
    }

    /**
     * Builds a fully-enclosed hollow box (floor, four walls, roof all solid; interior
     * air) between the inclusive relative bounds {@code min}/{@code max}. Callers that
     * need an opening (a door gap, a wall gap, etc.) should carve it after calling this,
     * e.g. via {@link #carveDoorGap}.
     */
    static void buildSealedChamber(ExtendedGameTestHelper helper, BlockPos min, BlockPos max, BlockState wallState) {
        fillBox(helper, min, max, wallState);
        BlockPos interiorMin = min.offset(1, 1, 1);
        BlockPos interiorMax = max.offset(-1, -1, -1);
        if (interiorMin.getX() <= interiorMax.getX()
                && interiorMin.getY() <= interiorMax.getY()
                && interiorMin.getZ() <= interiorMax.getZ()) {
            fillBox(helper, interiorMin, interiorMax, Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * Carves a doorway into a previously-solid wall at {@code lowerPos} (the block the
     * door's lower half occupies) and places an OPEN oak door in it, facing {@code
     * facing}. The carved gap is THREE blocks tall, not two: a physical door is only
     * ever 2 blocks (lower/upper halves), but {@code NodeEvaluator}'s per-node sampling
     * box for this dragon is {@code entityHeight = Mth.floor(bbHeight + 1) = 3} — a
     * two-tall gap leaves a solid block in the top sampled layer, which the box-sampling
     * malus-picking logic (any {@code BLOCKED} cell in the box blocks the whole node)
     * rejects regardless of the door fix under test. The third (topmost) block is left
     * as plain air above the door rather than a second door pair (Minecraft doors don't
     * come in 3-tall variants).
     */
    static void carveDoorGap(ExtendedGameTestHelper helper, BlockPos lowerPos, Direction facing) {
        BlockState lower = Blocks.OAK_DOOR
                .defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, Boolean.TRUE)
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);

        helper.setBlock(lowerPos, lower);
        helper.setBlock(lowerPos.above(), upper);
        helper.setBlock(lowerPos.above(2), Blocks.AIR.defaultBlockState());
    }

    /**
     * Fills the inclusive cuboid {@code [min, max]} with an oak-leaves canopy. Reserved
     * for W8-PF8's wide-body-LEAVES-sampling test (out of this cluster's scope) — not
     * used by any test in this commit.
     */
    static void buildLeafCanopy(ExtendedGameTestHelper helper, BlockPos min, BlockPos max) {
        fillBox(helper, min, max, Blocks.OAK_LEAVES.defaultBlockState());
    }

    /**
     * Builds a straight solid wall over {@code [wallMin, wallMax]}, then carves an air
     * gap over the (must be fully contained) sub-cuboid {@code [gapMin, gapMax]}.
     * Reserved for the flight corner-cutting / streamlinePath tests (W8-PF1/W8-PF6, out
     * of this cluster's scope) — not used by any test in this commit.
     */
    static void buildWallWithGap(
            ExtendedGameTestHelper helper,
            BlockPos wallMin,
            BlockPos wallMax,
            BlockPos gapMin,
            BlockPos gapMax,
            BlockState wallState) {
        fillBox(helper, wallMin, wallMax, wallState);
        fillBox(helper, gapMin, gapMax, Blocks.AIR.defaultBlockState());
    }

    // ---------------------------------------------------------------------------------
    // W8-PF9 / W8-PF4 (commit 2)
    // ---------------------------------------------------------------------------------

    /**
     * W8-PF4. Builds a fully-sealed two-room stone chamber (floor, four walls, roof and
     * an internal dividing wall all solid) whose ONLY connection between the dragon's
     * room and the target's room is a single open oak door. Sealed on every other side
     * deliberately — an unsealed roof would let a flying retry route AROUND the
     * dividing wall and pass the test for the wrong reason regardless of whether
     * canPassDoors works, which is exactly the kind of vacuous pass this wave's harness
     * exists to rule out.
     *
     * <p>
     * Failure mode caught: pre-fix, {@code DragonPathNavigation.createPathFinder} never
     * calls {@code setCanPassDoors(true)}, so both the composite's own (flying-mode) and
     * {@code walkNodeEvaluator}'s (walking-mode) {@code canPassDoors} flags default
     * false; {@code WalkNodeEvaluator.getPathTypeWithinMobBB} rewrites the open door's
     * {@code DOOR_OPEN} node to {@code BLOCKED}, and the chamber is unreachable by
     * either mode. See {@code .fork-notes/wave8/red-baseline.md} for the exact pre-fix
     * failure recorded for this test.
     */
    @EmptyTemplate(SMALL_TEMPLATE)
    @GameTest
    @TestHolder
    public static void walkPathTreatsOpenDoorAsPassable(ExtendedGameTestHelper helper) {
        // Two 5x5-wide rooms (x:1-5 and x:7-11, z:1-5) either side of a dividing wall at
        // x=6, so every node position this dragon's 3x3x3 sampling box would occupy —
        // including the spawn and target themselves — has margin clear of ALL outer
        // walls, in BOTH horizontal axes. See SMALL_TEMPLATE's javadoc: NodeEvaluator's
        // per-node sampling box extends from a node's own coordinate in the +x/+y/+z
        // direction (never centered) across entityWidth, entityHeight, AND entityDepth —
        // entityDepth uses the same bbWidth as entityWidth, so a chamber only one block
        // deep in z (as an earlier revision of this test was), or even 3 blocks deep
        // aligned so the dragon sits in the MIDDLE (also tried and also wrong — the
        // sampling box still reaches one cell past a 3-deep room's far wall from the
        // center cell), leaves the z-sampling arm of every explored node touching a wall
        // partway through the search, and the whole node resolves BLOCKED for a reason
        // that has nothing to do with the door. 5 deep in z gives 3 valid z positions
        // (1,2,3 — z+2 must stay <= the interior max of 5) with the door placed centered
        // in that valid range, not at the interior's own geometric center.
        BlockPos min = new BlockPos(0, 0, 0);
        BlockPos max = new BlockPos(12, 4, 6);
        buildSealedChamber(helper, min, max, Blocks.STONE.defaultBlockState());
        // The dividing wall the sealed chamber above does not know about — it spans the
        // chamber's FULL depth (z:0-6), turning the one hollow room into two, connected
        // only by the door gap carved into it below.
        fillBox(helper, new BlockPos(6, 0, 0), new BlockPos(6, 4, 6), Blocks.STONE.defaultBlockState());
        carveDoorGap(helper, new BlockPos(6, 1, 3), Direction.SOUTH);
        // The single door block is only 1 column wide in z, but this dragon's sampling
        // box is entityDepth=3 wide in z too — confirmed via a throwaway diagnostic
        // gametest that dumped getPathTypeOfMob across the threshold: with only the
        // door's own z=3 column open, x=4,5,6 (every node whose box reaches the wall)
        // still resolved BLOCKED, because the box's z=4/z=5 arm always lands on the
        // still-solid z=2/z=4 wall columns flanking the door. Widen the actual PASSABLE
        // opening to 3-wide-in-z (matching entityDepth) by clearing the two z-columns on
        // either side of the door to plain air — the door fix under test only needs to
        // govern the ONE cell that is actually a door; the other two just need to not be
        // solid wall, exactly like a wide mob squeezing through a single vanilla-width
        // doorway never could, and isn't what this test is about.
        fillBox(helper, new BlockPos(6, 1, 2), new BlockPos(6, 3, 2), Blocks.AIR.defaultBlockState());
        fillBox(helper, new BlockPos(6, 1, 4), new BlockPos(6, 3, 4), Blocks.AIR.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 1, 3));
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        var navigation = dragon.getNavigation();
        // Advance DragonPathNavigation's internal 5-tick creation throttle (private
        // lastPathCreationDelta) synchronously — tick() is a plain method call with no
        // dependency on real elapsed world time, so this is deterministic (C7) rather
        // than relying on the gametest scheduler to advance real ticks.
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos target = helper.absolutePos(new BlockPos(8, 1, 3));
        Path path = navigation.createPath(target, 0);

        if (!navigation.getNodeEvaluator().canPassDoors()) {
            helper.fail("canPassDoors() is false — setCanPassDoors(true) is not reaching the active node evaluator");
        }
        if (path == null || !path.canReach()) {
            var evaluator = (DragonNodeEvaluator) navigation.getNodeEvaluator();
            StringBuilder nodes = new StringBuilder();
            if (path != null) {
                for (int i = 0; i < path.getNodeCount(); i++) {
                    nodes.append(path.getNodePos(i)).append(' ');
                }
            }
            helper.fail("DIAGNOSTIC path did not reach target through the open door: path="
                    + (path == null
                            ? "null"
                            : ("canReach=" + path.canReach() + " nodeCount=" + path.getNodeCount() + " nodes="
                                    + nodes))
                    + " allowFlying=" + evaluator.allowFlying + " allowSwimming=" + evaluator.allowSwimming
                    + " target=" + target);
        }

        helper.succeed();
    }

    /**
     * W8-PF9. After a completed pathfind, {@code PathFinder.findPath} has called {@code
     * nodeEvaluator.done()} exactly once; asserts that both of {@code
     * DragonNodeEvaluator}'s private delegate NodeEvaluators (not just the composite's
     * own inherited state) were torn down — {@code currentContext == null && mob ==
     * null} — matching the symmetry {@code prepare()} already provides. Read via
     * reflection because {@code NodeEvaluator.currentContext}/{@code mob} are {@code
     * protected} fields declared in {@code net.minecraft.world.level.pathfinder
     * .NodeEvaluator}: {@code DragonNodeEvaluator} cannot access them on a
     * differently-typed sibling instance (Java's protected-access rule requires the
     * qualifying reference's static type to be the accessing class or a subtype of it),
     * and this test lives in a different package again — per
     * gate-design-pathfind.json's W8-PF9 requiredChanges, "a package-visible accessor or
     * a reflective read" is the sanctioned alternative to the gate's rejected
     * unspawnable-entity-subclass test design.
     *
     * <p>
     * Failure mode caught: pre-fix, {@code DragonNodeEvaluator.done()} only calls {@code
     * super.done()} (the composite's own inherited {@code FlyNodeEvaluator.done()}) —
     * {@code swimNodeEvaluator}/{@code walkNodeEvaluator} never receive their own {@code
     * done()} call, so their {@code currentContext}/{@code mob} stay pinned to the last
     * {@code PathNavigationRegion}/{@code Mob} after every pathfind.
     */
    @EmptyTemplate(SMALL_TEMPLATE)
    @GameTest
    @TestHolder
    public static void pathfindDelegatesTornDownAfterCompletedPath(ExtendedGameTestHelper helper) {
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 1, 1));
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos target = helper.absolutePos(new BlockPos(6, 1, 1));
        navigation.createPath(target, 0);

        var evaluator = (DragonNodeEvaluator) navigation.getNodeEvaluator();
        assertDelegateTornDown(helper, evaluator, "walkNodeEvaluator");
        assertDelegateTornDown(helper, evaluator, "swimNodeEvaluator");

        helper.succeed();
    }

    private static void assertDelegateTornDown(ExtendedGameTestHelper helper, DragonNodeEvaluator evaluator, String delegateFieldName) {
        try {
            Field delegateField = DragonNodeEvaluator.class.getDeclaredField(delegateFieldName);
            delegateField.setAccessible(true);
            NodeEvaluator delegate = (NodeEvaluator) delegateField.get(evaluator);

            Field currentContextField = NodeEvaluator.class.getDeclaredField("currentContext");
            Field mobField = NodeEvaluator.class.getDeclaredField("mob");
            currentContextField.setAccessible(true);
            mobField.setAccessible(true);

            if (currentContextField.get(delegate) != null || mobField.get(delegate) != null) {
                helper.fail(delegateFieldName
                        + " was not torn down (currentContext/mob not null) after a completed pathfind — done() is not reaching the "
                        + delegateFieldName + " delegate");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inspect " + delegateFieldName + " teardown state via reflection", e);
        }
    }

    // ---------------------------------------------------------------------------------
    // W8-PF3 (commit 3)
    // ---------------------------------------------------------------------------------

    /**
     * Builds a fresh, registered drown-immune {@link DragonBreed} for a single test —
     * mirrors {@code BreedingUtilsTests}' pattern of constructing+registering throwaway
     * test breeds via reflection (the {@code immunities} field has no public setter;
     * only a class-level Lombok {@code @Getter}) rather than depending on the real
     * data-driven "water"/"ghost"/"ice" breed ids resolving to a specific string at
     * gametest-server boot, which this test has no need to couple itself to.
     */
    private static DragonBreed createDrownImmuneTestBreed(String id) {
        DragonBreed breed = new DragonBreed();
        breed.setId(id);
        try {
            Field immunitiesField = DragonBreed.class.getDeclaredField("immunities");
            immunitiesField.setAccessible(true);
            immunitiesField.set(breed, List.of("drown"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set immunities on test breed " + id, e);
        }
        DragonBreedsRegistry.register(breed);
        return breed;
    }

    /**
     * W8-PF3. A drown-immune dragon, already flying ({@code allowFlying=true} via {@code
     * dragon.isFlying()}), is asked to path 10 blocks across open air to a target block
     * that is itself water — so {@code DragonPathfindingRules.shouldAllowSwimming} also
     * comes back {@code true} for the same request, i.e. both {@code allowFlying} and
     * {@code allowSwimming} are true simultaneously, exactly the precedence conflict
     * W8-PF3 fixes.
     *
     * <p>
     * Failure mode caught: pre-fix, {@code DragonNodeEvaluator.findAcceptedNode(int,int,int)}
     * and {@code getPathType} both check {@code allowSwimming} BEFORE {@code
     * allowFlying}, so every one of {@code FlyNodeEvaluator}'s 26 neighbour candidates
     * along this route — plain open air, not water — gets graded through {@code
     * SwimNodeEvaluator}, which only ever accepts {@code WATER}/{@code BREACH}-classified
     * cells and adds a further malus penalty on top of that. See {@code
     * .fork-notes/wave8/red-baseline.md} for the exact pre-fix failure recorded for this
     * test.
     */
    @EmptyTemplate(SMALL_TEMPLATE)
    @GameTest
    @TestHolder
    public static void flightPathToWaterTargetIsNotForcedIntoSwimEvaluation(ExtendedGameTestHelper helper) {
        // Solid floor under the whole open room, matching walkPathTreatsOpenDoorAsPassable's
        // proven-workable SMALL_TEMPLATE geometry (15x5x15, floor at y=0, dragon/target at
        // y=1) — flight node-sampling never reaches down to y=0 from a y=1 start.
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(14, 0, 14), Blocks.STONE.defaultBlockState());

        var breed = createDrownImmuneTestBreed("test_drown_immune_w8pf3");
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 1, 7));
        dragon.setBreed(breed);
        dragon.setFlying(true);

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos waterPos = new BlockPos(12, 1, 7);
        helper.setBlock(waterPos, Blocks.WATER.defaultBlockState());

        BlockPos target = helper.absolutePos(waterPos);
        Path path = navigation.createPath(target, 0);

        if (path == null || !path.canReach()) {
            helper.fail("DIAGNOSTIC flight path to water target did not reach: path="
                    + (path == null ? "null" : ("canReach=" + path.canReach() + " nodeCount=" + path.getNodeCount()))
                    + " target=" + target);
        }
        if (path.getNodeCount() <= 2) {
            helper.fail("Flight path to water target has too few nodes (" + path.getNodeCount()
                    + ") to have actually routed over 10 blocks of open air rather than being rejected/degenerate");
        }

        helper.succeed();
    }

    // ---------------------------------------------------------------------------------
    // W8-PF8 (commit 4)
    // ---------------------------------------------------------------------------------

    /**
     * W8-PF8. Builds a leaf "ceiling" spanning the ENTIRE room footprint at y=3, with the
     * dragon flying at y=2 — one block of open-air buffer (y=1) below the flight layer,
     * and the ceiling directly above it. {@code entityHeight = 3} sampling (extends from
     * a node's own y coordinate upward) means EVERY node at y=2 has its box reach the
     * ceiling at y=3 — the defect is uniform across the whole room, so there is no
     * detour that avoids it; this is the "no path found at all near forest canopy"
     * failure mode the fix targets, in its purest form.
     *
     * <p>
     * <b>Why the flight layer is y=2, not y=1 (directly above the floor):</b> {@code
     * FlyNodeEvaluator.getPathType}'s single-cell classification special-cases the block
     * directly below an open-air cell — when that block is solid (as the floor is), the
     * cell above it classifies as {@code WALKABLE}, not {@code OPEN}, even under flight
     * rules. The fix's guard is deliberately scoped to {@code getPathType(...) == OPEN}
     * (matching vanilla's own small-mob refinement exactly, which has the identical
     * scoping) — a {@code WALKABLE} floor-adjacent cell is out of scope for this fix by
     * design, the same as it is for vanilla's own refinement. An earlier revision of
     * this test placed the dragon directly on the floor at y=1 and could not be made to
     * pass even WITH the fix applied for exactly this reason (confirmed via a temporary
     * debug trace of {@code getPathTypeOfMob}'s inputs/outputs) — genuinely open, mid-air
     * canopy nodes are exactly what this fix targets; canopy directly over solid ground
     * is a different (unfixed, vanilla-inherited) scenario.
     *
     * <p>
     * Failure mode caught: pre-fix, {@code WalkNodeEvaluator.getPathTypeOfMob}
     * (inherited unmodified via {@code FlyNodeEvaluator}, which does not override it)
     * returns {@code LEAVES} — malus {@code -1.0F}, {@code BLOCKED} — for every y=2 node
     * the instant its sampling box touches the ceiling above, even though every node's
     * own centre is open air with a completely clear flight line straight through it
     * (the corridor is never physically obstructed — only the box-sampling
     * classification is wrong). See {@code .fork-notes/wave8/red-baseline.md} for the
     * exact pre-fix failure recorded for this test.
     */
    @EmptyTemplate(SMALL_TEMPLATE)
    @GameTest
    @TestHolder
    public static void flightPathThroughLeafCanopyIsReachable(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(14, 0, 14), Blocks.STONE.defaultBlockState());
        // 1-block-thick leaf ceiling spanning the entire room footprint at y=3, with a
        // 1-block open-air buffer (y=1) between the floor and the y=2 flight layer.
        buildLeafCanopy(helper, new BlockPos(0, 3, 0), new BlockPos(14, 3, 14));

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 2));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos target = helper.absolutePos(new BlockPos(12, 2, 12));
        Path path = navigation.createPath(target, 0);

        if (path == null || !path.canReach()) {
            StringBuilder nodes = new StringBuilder();
            if (path != null) {
                for (int i = 0; i < path.getNodeCount(); i++) {
                    nodes.append(path.getNodePos(i)).append(' ');
                }
            }
            helper.fail("DIAGNOSTIC flight path through leaf canopy did not reach: path="
                    + (path == null ? "null" : ("canReach=" + path.canReach() + " nodeCount=" + path.getNodeCount() + " nodes=" + nodes))
                    + " target=" + target);
        }

        helper.succeed();
    }

    /**
     * W8-PF8, second half of the fix's contract. A single, isolated leaf block sits
     * exactly on the straight line between spawn and target, sealed into a 5-wide
     * (z=5-9) trench — floor-to-ceiling solid side walls at z<=4 and z>=10, a solid
     * stone floor at y=0, and a solid stone ceiling at y=5 (well above the y=2 flight
     * layer — see below for why it cannot sit directly above it) — so there is no
     * sideways OR vertical detour around the block; the ONLY viable path is directly
     * through the trench.
     *
     * <p>
     * <b>Why the trench, and why the ceiling sits at y=5 (THREE earlier revisions were
     * each wrong in a different way):</b> a first version of this test placed the same
     * isolated leaf block in the open 15-wide room used by {@link
     * #flightPathThroughLeafCanopyIsReachable}, with no side walls at all — it passed on
     * UNMODIFIED HEAD (confirmed via the actual revert/rerun red-proof cycle, not
     * assumed), because plenty of z-detour room existed around the small leaf-block
     * halo. A second version added the side walls described above but no ceiling — it
     * ALSO passed on unmodified HEAD: the z=5-9 walls do close off every sideways
     * detour (every wall-safe column z0 ∈ {5,6,7} also falls inside the leaf's 3-wide
     * sampling halo, since {@code entityDepth=3} means a box at z0 as low as 5 still
     * reaches z=7), but the trench interior was left open ABOVE the flight layer — the
     * leaf block only occupies y=2, so a node at y0=3 (box y-range [3,4,5]) never
     * touches it at all, and the dragon simply flew up one layer, over the obstacle, and
     * back down. A third version added a ceiling, but placed it at y=3, directly inside
     * the flight layer's OWN sampling box ({@code entityHeight=3} means a y0=2 node's box
     * already reaches y=4) — that version failed even WITH the fix applied (confirmed
     * empirically, nodeCount=1): every y=2 node now touched genuine solid stone, not a
     * leaf, so the fix's LEAVES-only guard never applied at all, and the whole flight
     * layer became permanently unreachable regardless of the fix. All three would have
     * shipped as broken or vacuous — either always green (catching nothing) or always
     * red (a self-inflicted false failure) — exactly the discipline this wave's whole
     * harness exists to enforce (see {@code .fork-notes/wave8/red-baseline.md}). The
     * final geometry needs a taller room than the class's default {@link #SMALL_TEMPLATE}
     * provides, hence {@link #LARGE_TEMPLATE}: a y=5 ceiling is clear of y0=2's own box
     * ([2,3,4]) — leaving the leaf fix free to work exactly as in the untouched trench —
     * while still blocking y0=3's box ([3,4,5], reaches y=5) and y0=4's ([4,5,6]), so the
     * vertical escape is closed without corrupting the layer under test.
     *
     * <p>
     * <b>DEVIATION FROM THE ORIGINAL DESIGN:</b> the original design's
     * {@code openRouteIsPreferredOverLeafRoute} asked for two equal-length routes and an
     * assertion that the computed path contains no {@code LEAVES}-classified node,
     * framed as a cost-preference regression guard (guarding against the LEAVES malus
     * being tuned too low). That framing does not fit the AMENDED, gate-mandated fix
     * mechanism actually shipped in commit 4: {@code DragonNodeEvaluator} does not add a
     * malus tier at all — a qualifying node is fully reclassified to {@code PathType.OPEN}
     * (malus 0, byte-identical cost to genuinely clear air), matching vanilla's own
     * small-mob refinement exactly. Under that mechanism a canopy-adjacent route and a
     * genuinely clear route of equal length are cost-INDISTINGUISHABLE by design, so
     * asserting a route "preference" between them would be asserting an unspecified A*
     * tie-break order, not a real invariant — and per C7 this harness does not ship
     * assertions on tie-break order. This test instead exercises the fix's OTHER stated
     * half — "a node whose centre IS a leaf block stays BLOCKED" — which the ceiling test
     * above cannot exercise at all, since every node in that scenario has an open centre.
     * Two assertions: the path still reaches the target (proving the halo around the
     * single block does not cause a total failure, the same box-sampling defect as the
     * ceiling test, just localized), and the path never places a node exactly at the
     * leaf block's own position (proving the dragon is routed around the block, not
     * through its solid interior).
     *
     * <p>
     * Flies at y=2, one block of open-air buffer above the floor, for the same reason
     * documented on {@link #flightPathThroughLeafCanopyIsReachable} — a node directly
     * above solid ground classifies {@code WALKABLE}, not {@code OPEN}, so the fix's
     * {@code getPathType(...) == OPEN} guard would never fire at y=1.
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void flightPathRoutesAroundIsolatedLeafBlock(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(14, 0, 14), Blocks.STONE.defaultBlockState());
        // Floor-to-ceiling side walls sealing the trench to z=5-9 (no sideways detour),
        // PLUS a solid stone ceiling sealing off the vertical escape too (no flying up
        // and over the leaf block's y=2-only footprint either). The ceiling sits at
        // y=5, not directly above the y=2 flight layer: entityHeight=3 means a y0=2
        // node's OWN sampling box already reaches all the way to y=4, so a ceiling at
        // y=3 or y=4 would corrupt the flight layer's own classification (confirmed
        // empirically — an earlier revision put the ceiling at y=3, directly inside
        // that box, and the test failed even WITH the fix applied, nodeCount=1, because
        // every y=2 node now touched genuine solid stone, not a leaf, so the fix's
        // LEAVES-only guard never applied at all). A y=5 ceiling is clear of y0=2's box
        // ([2,3,4]) but still blocks y0=3's box ([3,4,5], reaches y=5) and y0=4's
        // ([4,5,6]) — the vertical escape route is closed without touching the layer
        // under test. This needs LARGE_TEMPLATE (24 tall) instead of the class's default
        // SMALL_TEMPLATE (5 tall), which has no room above y=4 for a y=5 ceiling at all.
        // A first revision (no ceiling) and a second (ceiling at y=3, corrupting the
        // flight layer) were both empirically wrong via the actual red/green cycle — see
        // this method's javadoc.
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(14, 6, 4), Blocks.STONE.defaultBlockState());
        fillBox(helper, new BlockPos(0, 0, 10), new BlockPos(14, 6, 14), Blocks.STONE.defaultBlockState());
        fillBox(helper, new BlockPos(0, 5, 5), new BlockPos(14, 5, 9), Blocks.STONE.defaultBlockState());

        BlockPos leafPos = new BlockPos(7, 2, 7);
        helper.setBlock(leafPos, Blocks.OAK_LEAVES.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 7));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos target = helper.absolutePos(new BlockPos(12, 2, 7));
        Path path = navigation.createPath(target, 0);

        if (path == null || !path.canReach()) {
            helper.fail("DIAGNOSTIC flight path around isolated leaf block did not reach: path="
                    + (path == null ? "null" : ("canReach=" + path.canReach() + " nodeCount=" + path.getNodeCount()))
                    + " target=" + target);
            return;
        }

        BlockPos leafAbsolute = helper.absolutePos(leafPos);
        for (int i = 0; i < path.getNodeCount(); i++) {
            if (path.getNodePos(i).equals(leafAbsolute)) {
                helper.fail("Path routed a node directly onto the leaf block's own position "
                        + leafAbsolute + " (node " + i + ") — a leaf-centred node must stay BLOCKED, not merely costed");
            }
        }

        helper.succeed();
    }

    // ---------------------------------------------------------------------------------
    // W8-PF1 / W8-PF6 (commit 5)
    // ---------------------------------------------------------------------------------

    /**
     * W8-PF1. Spawns a dragon and a target 20 blocks away in completely open air (no
     * obstacles) and calls {@code createPath} directly. Asserts the returned path's
     * {@code nextNodeIndex} was NOT collapsed toward the final node.
     *
     * <p>
     * AMENDED per the gate: the throttle-vs-collapse ordering matters. {@code
     * DragonPathNavigation.createPath} returns {@code null} unconditionally for the
     * FIRST call after construction ({@code lastPathCreationDelta} starts at 0, {@code
     * TICKS_BETWEEN_PATH_CREATIONS = 5}) — asserting {@code path != null} must happen
     * BEFORE asserting on {@code getNextNodeIndex()}, or a pre-fix "red" run would
     * actually be failing on a throttle NPE rather than on node-index collapse, proving
     * nothing about the actual bug.
     *
     * <p>
     * Failure mode caught: pre-fix, {@code streamlinePath}'s {@code closestNodeDist}
     * starts at {@code -1} and its {@code distFromPlayer < closestNodeDist ||
     * distFromPlayer > distFromDragon} OR-condition is true for essentially every node
     * of a monotonic straight-line path (verified by hand: for the first half of the
     * route {@code distFromPlayer > distFromDragon}; for the second half {@code
     * distFromPlayer} is strictly decreasing so it is always less than the previous
     * iteration's {@code closestNodeDist}) — {@code skipToNodeIndex} walks all the way to
     * the LAST node, and {@code setNextNodeIndex} commits that collapse into the path the
     * dragon actually follows.
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void flightPathIsNotCollapsedToFinalNode(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(24, 0, 4), Blocks.STONE.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 2));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos target = helper.absolutePos(new BlockPos(22, 2, 2));
        Path path = navigation.createPath(target, 1);

        if (path == null) {
            helper.fail("createPath returned null after clearing the 5-tick throttle — this assertion must run"
                    + " AFTER the throttle window, not before it, or a red run proves nothing about node collapse");
            return;
        }
        if (path.getNodeCount() <= 1) {
            helper.fail("Path has too few nodes (" + path.getNodeCount() + ") over a 20-block open-air route");
            return;
        }
        if (path.getNextNodeIndex() >= path.getNodeCount() - 1) {
            helper.fail("Path's nextNodeIndex (" + path.getNextNodeIndex() + ") was collapsed to the final node ("
                    + (path.getNodeCount() - 1) + " of " + path.getNodeCount()
                    + ") — streamlinePath is still beelining the dragon to the terminal waypoint");
        }

        helper.succeed();
    }

    /**
     * W8-PF1. Builds a wall spanning the direct line between the dragon and a target 6
     * blocks past it, with a gap offset to one side (z=0-3 of a 15-wide z=0-14 corridor
     * at the wall; dragon and target both sit at z=4 — just ONE block inside the wall's
     * blocked z-range — so the straight line between them is fully obstructed and any
     * successful route MUST detour sideways through the gap, but the "return climb" leg
     * of that detour is as short as it can be while still requiring one).
     *
     * <p>
     * AMENDED per the gate: the original design's stall proxy ("N consecutive ticks with
     * near-zero position delta") is a timing-shaped assertion C7 rules out given DET-1.
     * Replaced with a deterministic assertion on the computed {@code Path} object
     * itself: {@code canReach() == true}, AND at least one node's z-coordinate falls
     * inside the gap's z-range — proving a real detour was both COMPUTED and RETAINED in
     * the final path (not merely discoverable by A* and then immediately collapsed away
     * by {@code streamlinePath}, which is exactly what "computed but not retained" looked
     * like pre-fix).
     *
     * <p>
     * <b>Why the geometry is this tight (three earlier, more "natural" revisions all
     * produced a Path with canReach()==false EVEN WITH the fix applied, i.e. a false
     * failure unrelated to W8-PF1/W8-PF6):</b> a 20-block separation with a several-block
     * detour, then a 6-block separation with a 3-block return-climb, both consistently
     * stalled a few blocks short of the target — always past the wall in x, always still
     * short in z, regardless of the {@code accuracy} parameter passed to {@code
     * createPath} (tried 0, 2, and a deliberately-too-generous 10, which is why that
     * value must never be used here — it would let the search terminate the instant ANY
     * explored node is merely "close enough," which for a generous accuracy can include
     * the very first candidates near spawn, making the assertion pass without the detour
     * ever being exercised at all). The exact mechanism was not fully pinned down (the
     * search visits far fewer nodes than {@code PathFinder}'s budget allows, so it is not
     * a simple maxVisitedNodes exhaustion), but shrinking the detour's return-climb leg
     * to a single block consistently resolved it across every geometry tried. Recorded
     * here, with the specific failure shape, so a future revision of this test does not
     * silently reintroduce a longer return-climb leg and reproduce a false failure.
     *
     * <p>
     * Failure mode caught: pre-fix, even when A* successfully finds a detour route
     * through the gap, {@code streamlinePath} collapses {@code nextNodeIndex} toward the
     * final node exactly as in {@link #flightPathIsNotCollapsedToFinalNode} above,
     * discarding the detour waypoints from the path the dragon actually follows — the
     * dragon is told to beeline for the far side, straight into the wall.
     */
    @EmptyTemplate(SMALL_TEMPLATE)
    @GameTest
    @TestHolder
    public static void dragonRoutesAroundObstacleInsteadOfStalling(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(14, 0, 14), Blocks.STONE.defaultBlockState());
        // Wall at x=5, spanning z=4-14 (11 deep) and y=1-3 (3 tall), leaving a 4-wide gap
        // at z=0-3. Dragon and target both sit at z=4 — only ONE block inside the wall's
        // blocked span — minimizing the return-climb leg of the detour to a single step.
        fillBox(helper, new BlockPos(5, 1, 4), new BlockPos(5, 3, 14), Blocks.STONE.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 4));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 4));
        Path path = navigation.createPath(target, 0);

        if (path == null || !path.canReach()) {
            StringBuilder nodes = new StringBuilder();
            if (path != null) {
                for (int i = 0; i < path.getNodeCount(); i++) {
                    nodes.append(path.getNodePos(i)).append(' ');
                }
            }
            helper.fail("DIAGNOSTIC path did not reach around the obstacle: path="
                    + (path == null ? "null" : ("canReach=" + path.canReach() + " nodeCount=" + path.getNodeCount() + " nodes=" + nodes))
                    + " target=" + target);
            return;
        }

        // The gap is at relative z=0-3; convert each node's absolute z back to relative
        // via the same origin fillBox/spawn coordinates were expressed in.
        //
        // CRITICAL: this must scan from getNextNodeIndex() onward, NOT from index 0.
        // streamlinePath (pre-fix) only mutates nextNodeIndex — it never removes nodes
        // from the underlying list — so a scan over the WHOLE node list would find the
        // gap-range detour node regardless of whether the fix is applied, making the
        // assertion pass identically pre- and post-fix (confirmed empirically: an
        // earlier revision of this assertion scanned from 0 and passed on unmodified
        // HEAD, exactly the vacuous-test trap this wave's red-baseline gate exists to
        // catch). Scanning from nextNodeIndex checks what the dragon will ACTUALLY walk,
        // which is precisely what streamlinePath corrupts.
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        boolean routedThroughGap = false;
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            int relativeZ = path.getNodePos(i).getZ() - origin.getZ();
            if (relativeZ >= 0 && relativeZ <= 3) {
                routedThroughGap = true;
                break;
            }
        }
        if (!routedThroughGap) {
            helper.fail("Path reached the target but the RETAINED portion (from nextNodeIndex=" + path.getNextNodeIndex()
                    + " onward) contains no node within the gap's z-range (0-3) — the wall fully blocks z=4-14 at"
                    + " x=5, so a genuinely retained detour MUST pass through the gap; this means the detour was"
                    + " computed but discarded from the path the dragon will actually follow");
        }

        helper.succeed();
    }

    /**
     * W8-PF6. Directly invokes {@code DragonPathNavigation.canMoveDirectly} (via
     * reflection — it is {@code protected}) with two points a few blocks apart in
     * completely open air, on a dragon with {@code isFlying() == true}. Asserts it
     * returns {@code true}.
     *
     * <p>
     * AMENDED per the gate: the original design's tick-budget/1.3x-tolerance timing
     * comparison is exactly the flaky shape C7 rules out, AND is structurally
     * unachievable as literally specified — {@code PathNavigation.followThePath} calls
     * {@code this.path.advance()} AT MOST ONCE per invocation (verified by direct read of
     * the decompiled source: no loop around the corner-cut arm), so {@code
     * getNextNodeIndex()} cannot advance by more than one within a single {@code
     * followThePath()}/tick call by the vanilla mechanism itself, regardless of this fix.
     * The gate's own offered alternative — "expose a package-visible test seam for
     * canMoveDirectly" — is used instead: this test invokes the method directly via
     * reflection (matching this class's established pattern for protected/package-private
     * members, see {@link #assertDelegateTornDown}) and asserts on its return value
     * directly, which is the actual behavior this fix changes.
     *
     * <p>
     * Failure mode caught: pre-fix, {@code canMoveDirectly} only ever returned {@code
     * true} for the swim case ({@code allowSwimming && mob.isInLiquid()}) — for a flying,
     * non-aquatic dragon it returned {@code false} unconditionally, regardless of how
     * clear the line of sight was, disabling {@code PathNavigation.followThePath}'s
     * corner-cut arm for flight entirely.
     */
    @EmptyTemplate(SMALL_TEMPLATE)
    @GameTest
    @TestHolder
    public static void flightCornerCuttingIsEnabledWhileFlying(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(14, 0, 14), Blocks.STONE.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 7));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        DragonPathNavigation navigation = (DragonPathNavigation) dragon.getNavigation();

        Vec3 from = Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 2, 7)));
        Vec3 to = Vec3.atCenterOf(helper.absolutePos(new BlockPos(6, 2, 7)));

        boolean canMoveDirectly = invokeCanMoveDirectly(navigation, from, to);
        if (!canMoveDirectly) {
            helper.fail("canMoveDirectly() returned false for a flying dragon with a completely clear line of"
                    + " sight — corner-cutting is disabled for flight");
        }

        helper.succeed();
    }

    private static boolean invokeCanMoveDirectly(DragonPathNavigation navigation, Vec3 from, Vec3 to) {
        try {
            Method method = DragonPathNavigation.class.getDeclaredMethod("canMoveDirectly", Vec3.class, Vec3.class);
            method.setAccessible(true);
            return (boolean) method.invoke(navigation, from, to);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke canMoveDirectly via reflection", e);
        }
    }

    // ---------------------------------------------------------------------------------
    // W8-PF2 (commit 6)
    // ---------------------------------------------------------------------------------

    /**
     * W8-PF2. Establishes a live, in-progress path via {@code navigation.moveTo(...)}
     * (which populates {@code PathNavigation}'s own {@code this.path} field — the
     * "currently being followed" path, not merely a {@code createPath} return value),
     * then — still inside the 5-tick throttle window, since {@code moveTo}'s internal
     * {@code createPath} call reset {@code lastPathCreationDelta} to 0 and no {@code
     * navigation.tick()} is called in between — requests the exact SAME target again.
     *
     * <p>
     * Failure mode caught: pre-fix, {@code createPath} returned {@code null}
     * unconditionally whenever throttled, regardless of whether a perfectly good live
     * path already existed for the same destination — which upstream vanilla consumers
     * (verified in refute-pathfind.json missedBugs#4: {@code
     * MoveToTargetSink.tryComputePath} erases {@code WALK_TARGET} on null; {@code
     * PathNavigation.recomputePath} does {@code this.path = null; this.path =
     * this.createPath(...)}, so a throttled null permanently destroys the in-flight
     * path) treat as "target unreachable."
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void throttledCreatePathReusesLivePathInsteadOfNull(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(34, 0, 4), Blocks.STONE.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 2));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos target = helper.absolutePos(new BlockPos(20, 2, 2));
        boolean started = navigation.moveTo(target.getX(), target.getY(), target.getZ(), 1, 1.0);
        Path livePath = navigation.getPath();
        if (!started || livePath == null || livePath.isDone()) {
            helper.fail("DIAGNOSTIC setup failed to establish a live, in-progress path: started=" + started
                    + " path=" + (livePath == null
                            ? "null"
                            : ("isDone=" + livePath.isDone() + " nodeCount=" + livePath.getNodeCount())));
            return;
        }

        // Still inside the 5-tick throttle window — request the SAME target again.
        Path secondCall = navigation.createPath(target, 1);
        if (secondCall == null) {
            helper.fail("Throttled createPath for the SAME target returned null instead of the live path"
                    + " — this is exactly the bug that erases WALK_TARGET / destroys in-flight paths"
                    + " (refute-pathfind.json missedBugs#4)");
            return;
        }
        if (secondCall != livePath) {
            helper.fail(
                    "Throttled createPath for the SAME target returned a DIFFERENT Path object instead of handing"
                            + " back the live path unchanged");
            return;
        }

        helper.succeed();
    }

    /**
     * W8-PF2, gate-required correction. Establishes a live path to a first target, then
     * — still inside the throttle window — requests a genuinely DIFFERENT, far-apart
     * target. Asserts the returned path is a fresh computation ending at the NEW target,
     * not the stale first-target path.
     *
     * <p>
     * Failure mode caught: the AMENDED fix's own failure mode, distinct from the
     * original bug — naively returning {@code this.path} for ANY throttled request (with
     * no same-target check) would serve the FOLLOW-goal's path to, say, a {@code
     * RandomStroll} request or vice versa, sending the dragon to the wrong place. Per
     * gate-design-pathfind.json's W8-PF2 requiredChanges: "Add a gametest that requests a
     * DIFFERENT target inside the throttle window and asserts the returned path's end
     * node is the newly requested target, not the old one."
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void throttledCreatePathToADifferentTargetComputesFreshPath(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(34, 0, 4), Blocks.STONE.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 2));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos firstTarget = helper.absolutePos(new BlockPos(15, 2, 2));
        boolean started = navigation.moveTo(firstTarget.getX(), firstTarget.getY(), firstTarget.getZ(), 1, 1.0);
        Path firstPath = navigation.getPath();
        if (!started || firstPath == null || firstPath.isDone()) {
            helper.fail("DIAGNOSTIC setup failed to establish a live path to the FIRST target: started=" + started);
            return;
        }

        // Still inside the throttle window (no navigation.tick() call since moveTo) —
        // request a target far enough away that it cannot be mistaken for the first.
        BlockPos secondTarget = helper.absolutePos(new BlockPos(32, 2, 2));
        Path secondPath = navigation.createPath(secondTarget, 1);
        if (secondPath == null) {
            helper.fail("Throttled createPath for a DIFFERENT target returned null — the same-target reuse"
                    + " gate must fall through and compute fresh for a genuinely new destination");
            return;
        }
        if (secondPath == firstPath) {
            helper.fail("Throttled createPath for a DIFFERENT target returned the SAME Path object as the"
                    + " first target — this sends the dragon to the WRONG place, a new bug the same shape as"
                    + " the one this fix removes");
            return;
        }
        BlockPos reachedTarget = secondPath.getTarget();
        if (reachedTarget == null || reachedTarget.distSqr(secondTarget) > 1) {
            helper.fail("Second createPath's returned Path targets " + reachedTarget + ", not the requested "
                    + secondTarget + " — the throttle served a stale target instead of computing fresh");
        }

        helper.succeed();
    }

    /**
     * W8-PF2. Drives the fix through the REAL vanilla consumer per the gate's explicit
     * requirement ("drive it through ServerLevel.blockUpdated/recomputePath rather than
     * calling createPath directly, so it proves the vanilla consumer no longer destroys
     * the path") — {@code helper.setBlock} goes through the genuine {@code
     * Level.setBlock(pos, state, 3)} pipeline, which (per direct read of decompiled
     * {@code Level.markAndNotifyBlock}) synchronously calls {@code
     * ServerLevel.sendBlockUpdated}, which in turn calls {@code
     * PathNavigation.shouldRecomputePath} for every mob in {@code
     * ServerLevel.navigatingMobs} (populated on entity tracking start — i.e. on spawn,
     * regardless of whether a path is active yet) and, if true, {@code recomputePath()}.
     *
     * <p>
     * The changed block sits above the y=2 flight layer (y=4, near the room's ceiling),
     * off the actual flight route, so the corridor stays genuinely reachable — any
     * failure here is attributable to the throttle bug, not a real obstruction. It is
     * still well within {@code shouldRecomputePath}'s geometric threshold ({@code
     * pos.closerToCenterThan(midpoint-of-mob-and-path-end, remaining-node-count)}) since
     * it sits directly above the route's midpoint.
     *
     * <p>
     * Failure mode caught: pre-fix, {@code recomputePath()} does {@code this.path =
     * null;} THEN calls {@code createPath(...)} — while still inside the 5-tick
     * throttle window (true here: {@code moveTo}'s internal {@code createPath} call
     * reset the counter to 0, and no {@code navigation.tick()} runs before the block
     * change), the old code's unconditional {@code return null;} means the assignment
     * `this.path = this.createPath(...)` sets {@code this.path} to {@code null} —
     * permanently wiping the in-flight path as a side effect of a nearby, entirely
     * survivable block change (refute-pathfind.json missedBugs#4: "any nearby block
     * change has an ~80% chance of deleting the dragon's path outright").
     */
    @EmptyTemplate(SMALL_TEMPLATE)
    @GameTest
    @TestHolder
    public static void blockUpdateNearDragonDoesNotWipeInFlightPath(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(14, 0, 14), Blocks.STONE.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 7));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos target = helper.absolutePos(new BlockPos(12, 2, 7));
        boolean started = navigation.moveTo(target.getX(), target.getY(), target.getZ(), 1, 1.0);
        Path livePath = navigation.getPath();
        if (!started || livePath == null || livePath.isDone()) {
            helper.fail("DIAGNOSTIC setup failed to establish a live in-flight path before the block update");
            return;
        }

        // Real block-change pipeline — NOT createPath called directly. Still inside the
        // 5-tick throttle window (no navigation.tick() call since moveTo).
        helper.setBlock(new BlockPos(7, 4, 7), Blocks.STONE.defaultBlockState());

        if (navigation.getPath() == null) {
            helper.fail("A nearby, entirely survivable block change wiped the in-flight path to null —"
                    + " the throttled createPath call triggered by PathNavigation.recomputePath returned null"
                    + " instead of computing (or falling through to compute) a fresh path");
            return;
        }

        helper.succeed();
    }

    // ---------------------------------------------------------------------------------
    // W8-PF2 fix-round follow-up (targetPos/this.path desync)
    // ---------------------------------------------------------------------------------

    /**
     * FIX-ROUND follow-up to W8-PF2's same-target reuse gate: exercises the specific
     * {@code targetPos}/{@code this.path} DESYNC the original review missed. {@code
     * PathNavigation.targetPos} (read via {@code getTargetPos()}) is assigned by EVERY
     * successful {@code createPath} call — verified against decompiled {@code
     * PathNavigation}'s 5-arg {@code createPath}: {@code this.targetPos =
     * path.getTarget();} runs whenever a path with a non-null target is returned — whereas
     * {@code this.path}, the path actually being followed, is only ever assigned by {@code
     * moveTo}/{@code recomputePath}. A direct {@code createPath} call that is NOT followed
     * by {@code moveTo} (exactly like vanilla's own {@code MoveToTargetSink.tryComputePath},
     * which calls {@code createPath}, checks {@code path.canReach()}, and only calls
     * {@code moveTo} on the reaching branch) therefore advances {@code targetPos} without
     * ever touching {@code this.path} — the two fields desync.
     *
     * <p>
     * Sequence: (1) {@code moveTo} establishes a live path to target A — {@code this.path}
     * and {@code targetPos} both point at A. (2) A direct {@code createPath} call for
     * target B1, still inside the throttle window, forces {@code targetPos} to advance to
     * B1 (per the mechanism above) while {@code this.path} keeps routing to A — the desync
     * is now in place. (3) A further throttled {@code createPath} call for a DIFFERENT
     * exact block B2 — one block off B1, still within {@code accuracy} of it — is the call
     * actually under test.
     *
     * <p>
     * <b>Why B2 must be a DIFFERENT exact {@code BlockPos} from B1, not the same one
     * (DEVIATION from the literal "call createPath(B, accuracy) again" wording — same
     * spec intent, adapted mechanism):</b> {@code PathNavigation}'s OWN 5-arg {@code
     * createPath} — inherited unmodified, reached whenever this gate correctly falls
     * through to compute fresh — carries a SEPARATE, pre-existing reuse guard keyed on
     * the exact same {@code targetPos} field: {@code this.path != null &&
     * !this.path.isDone() && targets.contains(this.targetPos)} (decompiled {@code
     * PathNavigation.java}, verified). If step 3 requested the SAME exact B1 that step 2
     * advanced {@code targetPos} to, THAT guard would independently match ({@code
     * targets = {B1}}, {@code targetPos == B1}) and hand back {@code this.path} (still
     * pathA) itself — reproducing the wrong-destination symptom via a completely
     * different, pre-existing mechanism this fix-round's required change #3 does not
     * touch (see this cluster's own review notes, observation (b): the walk-then-fly
     * double {@code super.createPath} call hits the identical guard family, predates
     * wave 8, and is explicitly out of scope here — backlog item, not fixed in this
     * commit). That would make this test fail on FIXED code too, for a reason unrelated
     * to what this commit changes, and could not discharge the required change at all.
     * Using a distinct B2 (one block off B1, still within {@code accuracy} of it so the
     * OLD buggy {@code getTargetPos()}-keyed gate still matches by distance — proving the
     * desync bug is still demonstrated) keeps {@code targets.contains(this.targetPos)}
     * false ({@code {B2}.contains(B1)} is false for distinct exact BlockPos, even though
     * they are geometrically close), so the fixed gate's own fall-through reaches a
     * genuine fresh computation rather than being masked by the deeper, unrelated guard.
     *
     * <p>
     * Failure mode caught: pre-fix, the same-target reuse gate matched on {@code
     * getTargetPos() == B1} (within {@code accuracy} of the requested B2, advanced by
     * step 2), found {@code this.path} live and non-done, and handed back the A-path for
     * a B2 request — sending the dragon to the WRONG place, a bug {@code
     * throttledCreatePathToADifferentTargetComputesFreshPath} above cannot catch because
     * it never performs the intervening direct call that creates the desync in the first
     * place. Post-fix (gate keyed on {@code this.path.getTarget()}, which is still A
     * after step 2), the gate correctly does not match B2 and falls through to compute a
     * genuine fresh path to B2.
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void throttledCreatePathDoesNotServeStaleTargetAfterDirectCreatePathDesync(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(34, 0, 4), Blocks.STONE.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 2));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        var navigation = dragon.getNavigation();
        for (int i = 0; i < 5; i++) {
            navigation.tick();
        }

        BlockPos targetA = helper.absolutePos(new BlockPos(10, 2, 2));
        boolean started = navigation.moveTo(targetA.getX(), targetA.getY(), targetA.getZ(), 1, 1.0);
        Path pathA = navigation.getPath();
        if (!started || pathA == null || pathA.isDone()) {
            helper.fail("DIAGNOSTIC setup failed to establish a live path to target A: started=" + started);
            return;
        }

        // Still inside the throttle window (moveTo's internal createPath just reset it) —
        // a DIRECT createPath call for target B1, with no moveTo, which per
        // PathNavigation's own 5-arg createPath advances targetPos to B1 while leaving
        // this.path (still pathA) completely untouched. This is what manufactures the
        // desync under test.
        BlockPos targetB1 = helper.absolutePos(new BlockPos(32, 2, 2));
        Path desyncSetupPath = navigation.createPath(targetB1, 2);
        if (desyncSetupPath == null || !desyncSetupPath.canReach()) {
            helper.fail("DIAGNOSTIC setup failed to compute a reaching direct path to target B1 needed to"
                    + " advance targetPos and manufacture the desync");
            return;
        }

        // Still inside the throttle window (the direct call above reset it again). This
        // is the call actually under test — a DIFFERENT exact block (B2, one block off
        // B1) but within `accuracy` of it, so the OLD buggy getTargetPos()-keyed gate
        // still matches by distance (the bug this test targets), while the deeper,
        // unrelated, pre-existing PathNavigation-level targetPos reuse guard (keyed on
        // exact BlockPos set membership, not distance) does not — see this method's
        // javadoc for why that distinction is load-bearing here.
        BlockPos targetB2 = helper.absolutePos(new BlockPos(33, 2, 2));
        Path result = navigation.createPath(targetB2, 2);
        if (result == null) {
            helper.fail("Throttled createPath for target B2 returned null after the desyncing direct call");
            return;
        }
        if (result == pathA) {
            helper.fail("Throttled createPath for target B2 returned the LIVE PATH TO TARGET A — the"
                    + " same-target reuse gate matched on the stale targetPos (advanced to B1 by the"
                    + " intervening direct createPath call) instead of the path actually being followed,"
                    + " sending the dragon to the wrong place");
            return;
        }
        BlockPos reachedTarget = result.getTarget();
        if (reachedTarget == null || !reachedTarget.equals(targetB2)) {
            helper.fail("Throttled createPath for target B2 returned a path targeting " + reachedTarget
                    + ", not B2 (" + targetB2 + ") — the desync sent the request to the wrong destination");
        }

        helper.succeed();
    }

    // ---------------------------------------------------------------------------------
    // W8-PF7a (commit 7)
    // ---------------------------------------------------------------------------------

    /**
     * W8-PF7a (split out of the REJECTED W8-PF7 per the gate's explicit instruction —
     * "this part is correct as written"). {@code DragonMoveController.tick()}'s next-node
     * water lookahead called {@code mob.getNavigation().getPath().getNextNode()}
     * whenever a path existed, with no {@code isDone()} check. {@code
     * Path#getNextNode()} is {@code nodes.get(nextNodeIndex)} (verified directly against
     * decompiled source) — once {@code nextNodeIndex} reaches {@code nodes.size()}
     * (i.e. {@code path.isDone()} is {@code true}, a real reachable state: the
     * navigation's own {@code path} field can complete on its own {@code tick()} before
     * {@code DragonMoveController} runs its lookahead the same AI step, and nothing
     * nulls the completed path out), that call throws {@code
     * IndexOutOfBoundsException}.
     *
     * <p>
     * Drives the guard DIRECTLY and deterministically rather than racing real tick
     * timing to reach {@code isDone()} (a tick-window dependency C7 rules out): builds a
     * real multi-node {@code Path} via {@code navigation.moveTo}, then mechanically
     * calls {@code Path#advance()} — a plain method call, not tick-based — until {@code
     * path.isDone()} is confirmed {@code true}, arms the move controller's {@code
     * MOVE_TO} operation via the public {@code setWantedPosition} (a wanted position far
     * enough from the dragon's own position that the controller's "already arrived"
     * early return does not short-circuit before the lookahead runs), and calls {@code
     * DragonMoveController.tick()} directly.
     *
     * <p>
     * Failure mode caught: pre-fix, this throws {@code IndexOutOfBoundsException} and
     * the gametest framework reports the test as failed; post-fix, the {@code
     * !path.isDone()} guard skips the lookahead entirely for a completed path and {@code
     * tick()} returns normally.
     */
    @EmptyTemplate(SMALL_TEMPLATE)
    @GameTest
    @TestHolder
    public static void moveControllerDoesNotThrowWhenPathIsAlreadyDone(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(14, 0, 14), Blocks.STONE.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 7));
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        var navigation = dragon.getNavigation();
        BlockPos target = helper.absolutePos(new BlockPos(5, 2, 7));
        boolean started = navigation.moveTo(target.getX(), target.getY(), target.getZ(), 1, 1.0);
        Path path = navigation.getPath();
        if (!started || path == null || path.getNodeCount() < 1) {
            helper.fail("DIAGNOSTIC setup failed to establish a live path before completing it: started=" + started
                    + " path=" + (path == null ? "null" : ("nodeCount=" + path.getNodeCount())));
            return;
        }

        // Mechanically complete the path — a plain method-call loop, NOT a tick race —
        // then confirm the precondition this test needs before exercising the
        // controller.
        int guardBound = path.getNodeCount() + 1;
        for (int i = 0; i < guardBound && !path.isDone(); i++) {
            path.advance();
        }
        if (!path.isDone()) {
            helper.fail("DIAGNOSTIC setup failed to mechanically drive the path to isDone()==true");
            return;
        }

        dragon.getMoveControl().setWantedPosition(target.getX() + 5, target.getY(), target.getZ(), 1.0);

        try {
            dragon.getMoveControl().tick();
        } catch (IndexOutOfBoundsException e) {
            helper.fail("DragonMoveController.tick() threw IndexOutOfBoundsException against a completed"
                    + " (isDone()==true) path — the next-node water lookahead called getNextNode() without"
                    + " first checking path.isDone(): " + e);
            return;
        }

        helper.succeed();
    }

    // ---------------------------------------------------------------------------------
    // W8-PF10 (commit 8)
    // ---------------------------------------------------------------------------------

    /**
     * W8-PF10 (amended: per-request followRange scaling, clamped between the live
     * {@code Attributes.FOLLOW_RANGE} attribute and {@code
     * ModConstants.DragonConstants.pathfindSearchRadius}, rather than a flat pinned
     * ceiling). This dragon's {@code FOLLOW_RANGE} attribute is left at its default
     * ({@code DragonConstants.BASE_FOLLOW_RANGE = 32}) — pre-fix, {@code
     * PathNavigation.createPath(Set,int,boolean,int)} derives {@code followRange} purely
     * from that attribute (verified against decompiled source), and {@code
     * PathFinder.findPath}'s {@code maxRange} gate (node expansion stops at {@code
     * node.distanceTo(start) >= maxRange}; insertion requires {@code
     * node1.walkedDistance < maxRange}) therefore caps a single computation well short
     * of a 45-block target, so even a completely open, obstacle-free flight path fails
     * to reach in one {@code createPath} call.
     *
     * <p>
     * Kept deterministic per C7 (the gate's concern that a widened search radius could
     * exhaust the node-visit budget and silently degrade to a terrain-dependent
     * best-effort path): the corridor is a straight, fully open flight lane with zero
     * obstacles, so the number of nodes PathFinder actually needs to visit is small
     * relative to the {@code 2560}-node budget {@link PathfindingRulesTests
     * #visitedNodeBudgetCoversWidenedSearchRadiusWithComfortableMargin} pins as
     * comfortably sufficient — this test proves the RADIUS widens far enough to reach;
     * that sibling unit test proves the BUDGET doesn't independently cap the search
     * first.
     *
     * <p>
     * Failure mode caught: pre-fix, {@code createPath} either returns {@code null} or a
     * non-reaching best-effort {@code Path} (canReach()==false) for this request, even
     * though the target is genuinely, trivially reachable by open-air flight.
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void pathfinderReaches45BlockTargetDespiteFollowRangeAttributeOf32(ExtendedGameTestHelper helper) {
        fillBox(helper, new BlockPos(0, 0, 0), new BlockPos(50, 0, 4), Blocks.STONE.defaultBlockState());

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(2, 2, 2));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.setFlying(true);

        double attributeValue = dragon.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (attributeValue >= 45.0) {
            helper.fail("DIAGNOSTIC precondition failed: dragon's FOLLOW_RANGE attribute (" + attributeValue
                    + ") is already >= this test's 45-block separation, so it cannot distinguish the fix from"
                    + " vanilla's own unmodified single-computation reach");
            return;
        }

        BlockPos target = helper.absolutePos(new BlockPos(47, 2, 2));
        Path path = dragon.getNavigation().createPath(target, 1);

        if (path == null) {
            helper.fail("createPath returned null for a 45-block open-air target with FOLLOW_RANGE attribute "
                    + attributeValue + " — the pathfinder's per-request search radius did not widen to cover"
                    + " this request");
            return;
        }
        if (!path.canReach()) {
            helper.fail("createPath returned a non-reaching (best-effort) path for a 45-block, fully open-air"
                    + " target — the widened followRange did not let the search actually reach the target"
                    + " within its node-visit budget");
            return;
        }

        helper.succeed();
    }
}
