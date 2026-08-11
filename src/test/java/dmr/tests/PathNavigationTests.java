package dmr.tests;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.annotation.ForEachTest;
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
 *       sealed chambers) within roughly an 8-block footprint. Used by this cluster's own
 *       tests.
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

    /** Local single-obstacle geometry: walls, gaps, canopies, sealed chambers. */
    static final String SMALL_TEMPLATE = "9x5x9";

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
     * Carves a two-block-tall doorway into a previously-solid wall at {@code lowerPos}
     * (the block the door's lower half occupies) and places an OPEN oak door in it,
     * facing {@code facing}.
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
}
