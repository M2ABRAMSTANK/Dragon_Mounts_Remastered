package dmr.tests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dmr.DragonMounts.ModConstants.DragonConstants;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler.DragonInstance;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler.SnapshotRespawnDecision;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plain JUnit unit tests (Wave 5) for the pure decision logic extracted out of {@link
 * DragonWhistleHandler}'s snapshot-respawn path. Unlike the rest of this fork's test
 * suite (see {@link CommunityRegressionTests}), these run under {@code ./gradlew test}
 * — no gametest server, no real chunk loads/unloads — because both methods under test
 * take their world-state inputs as injected parameters (fix-plan.md's Wave 0 item 7
 * precedent: {@code CompleteDataSync.shouldApplyToLocalPlayer}).
 */
public class DragonWhistleHandlerLogicTests {

    private static final List<ChunkPos> THREE_CHUNKS =
            List.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(0, 1));

    /**
     * T3 (Fix B2 decision table): any chunk in the search area not entity-loaded must
     * refuse — and must refuse WITHOUT even evaluating {@code dragonFoundLive}, since
     * production wires that supplier to an expensive all-levels sweep that should never
     * run when the chunk gate already fails.
     */
    @Test
    void anyChunkNotLoadedRefusesWithoutEvaluatingDragonFoundLive() {
        var decision = DragonWhistleHandler.decideSnapshotRespawn(
                null, THREE_CHUNKS, (level, chunkPos) -> !chunkPos.equals(new ChunkPos(1, 0)), () -> {
                    throw new AssertionError("dragonFoundLive must not be evaluated when a chunk gate fails");
                });

        assertEquals(SnapshotRespawnDecision.CHUNK_NOT_LOADED, decision);
    }

    /** T3: every chunk loaded, but the dragon was found live somewhere — never mint. */
    @Test
    void allLoadedButDragonFoundRefuses() {
        var decision =
                DragonWhistleHandler.decideSnapshotRespawn(null, THREE_CHUNKS, (level, chunkPos) -> true, () -> true);

        assertEquals(SnapshotRespawnDecision.DRAGON_STILL_PRESENT, decision);
    }

    /**
     * T3: every chunk loaded and the dragon was NOT found anywhere — the only decision
     * table row that may mint a snapshot clone.
     */
    @Test
    void allLoadedAndAbsentAllows() {
        var decision =
                DragonWhistleHandler.decideSnapshotRespawn(null, THREE_CHUNKS, (level, chunkPos) -> true, () -> false);

        assertEquals(SnapshotRespawnDecision.ALLOW, decision);
    }

    /**
     * T3 edge case: an empty chunk list trivially satisfies the loaded-gate (nothing to
     * check), so the decision rests entirely on {@code dragonFoundLive}.
     */
    @Test
    void emptyChunkListRestsOnDragonFoundLive() {
        var decision = DragonWhistleHandler.decideSnapshotRespawn(
                null,
                List.of(),
                (level, chunkPos) -> {
                    throw new AssertionError("no chunks to probe");
                },
                () -> false);

        assertEquals(SnapshotRespawnDecision.ALLOW, decision);
    }

    /**
     * T4 (Fix B5): a dimension string in the legacy {@code ResourceKey#toString()}
     * shape ("ResourceKey[minecraft:dimension / minecraft:overworld]", not a valid
     * {@code ResourceLocation}) must resolve to {@code null} instead of throwing.
     * {@code server=null} is safe here: the malformed string makes
     * {@code ResourceLocation.parse} throw before {@code server} would ever be
     * dereferenced.
     */
    @Test
    void malformedDimensionResolvesToNullInsteadOfThrowing() {
        var instance = new DragonInstance(
                "ResourceKey[minecraft:dimension / minecraft:overworld]", UUID.randomUUID(), UUID.randomUUID());

        assertNull(DragonWhistleHandler.resolveStoredLevel(null, instance));
    }

    /** T4: a null dimension string is treated as "unknown" without even attempting a parse. */
    @Test
    void nullDimensionResolvesToNull() {
        var instance = new DragonInstance((String) null, UUID.randomUUID(), UUID.randomUUID());

        assertNull(DragonWhistleHandler.resolveStoredLevel(null, instance));
    }

    /**
     * Test (iv) (Wave 5 review HIGH-3): {@code searchBoxChunks} must mirror vanilla
     * {@code EntitySectionStorage}'s query-AABB inflation ({@code aabb.inflate(2.0)}
     * before sectioning) exactly, or the honest gate would certify a chunk ring as
     * "loaded" that the widened-radius scan can actually read entities out of. Checked
     * at EVERY x/z-mod-16 offset (not just the four named in the review — {2, 3, 12,
     * 13} — since the bug is symmetric on both axes and this is cheap to run
     * exhaustively) by comparing against the same inflate-then-floor-divide formula
     * vanilla's section sweep uses.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void searchBoxChunksMatchesVanillaInflatedSweep(int mod16) {
        // Center comfortably away from 0 so x - reach stays negative for small mod16
        // values too (exercises the floor-division-of-negatives edge case).
        int base = 512;
        BlockPos center = new BlockPos(base + mod16, 70, base + mod16);

        double reach = DragonConstants.DRAGON_SEARCH_RADIUS / 2.0 + DragonWhistleHandler.SEARCH_BOX_INFLATION_BLOCKS;
        int expectedMinChunk = Math.floorDiv((int) Math.floor(center.getX() - reach), 16);
        int expectedMaxChunk = Math.floorDiv((int) Math.floor(center.getX() + reach), 16);

        var chunks = DragonWhistleHandler.searchBoxChunks(center);

        assertTrue(
                chunks.contains(new ChunkPos(expectedMinChunk, expectedMinChunk)),
                "searchBoxChunks at x/z mod16=" + mod16 + " is missing the vanilla-inflated MIN chunk "
                        + expectedMinChunk);
        assertTrue(
                chunks.contains(new ChunkPos(expectedMaxChunk, expectedMaxChunk)),
                "searchBoxChunks at x/z mod16=" + mod16 + " is missing the vanilla-inflated MAX chunk "
                        + expectedMaxChunk);
        assertTrue(
                !chunks.contains(new ChunkPos(expectedMinChunk - 1, expectedMinChunk - 1)),
                "searchBoxChunks at x/z mod16=" + mod16 + " over-covers past the vanilla-inflated MIN chunk");
        assertTrue(
                !chunks.contains(new ChunkPos(expectedMaxChunk + 1, expectedMaxChunk + 1)),
                "searchBoxChunks at x/z mod16=" + mod16 + " over-covers past the vanilla-inflated MAX chunk");
    }

    /**
     * Test (iv): couples {@code SUMMON_CHUNK_TICKET_RADIUS} to {@code
     * searchBoxChunks}'s actual output so a future {@code DRAGON_SEARCH_RADIUS} (or
     * inflation) change that outgrows the ticket radius fails HERE instead of silently
     * reopening the B1/B2 gap (a chunk the honest gate requires loaded, but the region
     * ticket never actually held open). Exhaustive over every x/z-mod-16 offset —
     * cheap, and the whole point is "worst case", not "typical case".
     */
    @Test
    void ticketRadiusCoversWorstCaseSearchBox() {
        int base = 512;
        for (int xMod = 0; xMod < 16; xMod++) {
            for (int zMod = 0; zMod < 16; zMod++) {
                BlockPos lastPos = new BlockPos(base + xMod, 70, base + zMod);
                ChunkPos centerChunk = new ChunkPos(lastPos);

                for (ChunkPos required : DragonWhistleHandler.searchBoxChunks(lastPos)) {
                    int dx = Math.abs(required.x - centerChunk.x);
                    int dz = Math.abs(required.z - centerChunk.z);
                    assertTrue(
                            Math.max(dx, dz) <= DragonWhistleHandler.SUMMON_CHUNK_TICKET_RADIUS,
                            "chunk " + required + " required entity-loaded by searchBoxChunks at lastPos " + lastPos
                                    + " (chunk " + centerChunk + ") exceeds SUMMON_CHUNK_TICKET_RADIUS="
                                    + DragonWhistleHandler.SUMMON_CHUNK_TICKET_RADIUS);
                }
            }
        }
    }

    /**
     * W8-SUMMON-2 (null-UUID hardening): a {@link DragonInstance} whose {@code
     * dragonUUID} is null (the blank-legacy-string edge case gate-compat
     * constraint-d1 flagged) must not NPE when written to NBT — this write is
     * unconditional and reachable from {@code DragonOwnerCapability#serializeNBT},
     * i.e. from PLAYER SAVE. Pre-fix, {@code CompoundTag#putUUID("uuid", null)}
     * throws (it forwards to {@code UUIDUtil.uuidToIntArray}, which dereferences the
     * UUID); post-fix the "uuid" key is simply omitted.
     */
    @Test
    void writeNbtOmitsUuidKeyWhenDragonUuidIsNull() {
        var instance = new DragonInstance("minecraft:overworld", UUID.randomUUID(), null);

        CompoundTag tag = assertDoesNotThrow(instance::writeNBT, "writeNBT must not NPE on a null dragonUUID");

        assertFalse(tag.contains("uuid"), "writeNBT must omit the \"uuid\" key rather than write a null sentinel");
    }

    /**
     * W8-SUMMON-1a: a dragon exactly AT the threshold must still walk — inclusive
     * {@code <=} semantics unchanged from the pre-fix {@code BASE_FOLLOW_RANGE *
     * FOLLOW_RANGE_MULTIPLIER} constant check. Default config (0.0 = live attribute):
     * a dragon 32 blocks from its owner (the default generic.follow_range) walks.
     */
    @Test
    void isWithinWalkRangeIsInclusiveAtTheBoundaryWithDefaultConfig() {
        assertTrue(
                DragonWhistleHandler.isWithinWalkRange(32.0, 0.0, 32.0),
                "distance exactly equal to the live follow_range attribute must still walk");
        assertFalse(
                DragonWhistleHandler.isWithinWalkRange(32.000001, 0.0, 32.0),
                "distance a hair beyond the live follow_range attribute must teleport, not walk");
    }

    /**
     * W8-SUMMON-1a config escape hatch: an operator-set {@code
     * SUMMON_WALK_MAX_DISTANCE=64} restores the pre-wave-8 64-block walk radius (the
     * old {@code BASE_FOLLOW_RANGE(32) * FOLLOW_RANGE_MULTIPLIER(2.0)} product), still
     * inclusive at the boundary, regardless of the live follow_range attribute value.
     */
    @Test
    void isWithinWalkRangeHonorsConfiguredOverrideAtTheBoundary() {
        assertTrue(
                DragonWhistleHandler.isWithinWalkRange(64.0, 64.0, 32.0),
                "an explicit 64-block override must restore the old inclusive boundary");
        assertFalse(
                DragonWhistleHandler.isWithinWalkRange(64.000001, 64.0, 32.0),
                "distance a hair beyond the configured override must teleport, not walk");
    }
}
