package dmr.tests;

import dmr.DragonMounts.registry.DragonBreedsRegistry;
import dmr.DragonMounts.registry.ModEntities;
import dmr.DragonMounts.server.ai.navigation.DragonNodeEvaluator;
import java.lang.reflect.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
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
}
