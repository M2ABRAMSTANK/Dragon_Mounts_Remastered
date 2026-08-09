package dmr.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler.DragonInstance;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler.SnapshotRespawnDecision;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

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
}
