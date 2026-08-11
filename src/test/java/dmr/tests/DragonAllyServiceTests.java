package dmr.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dmr.DragonMounts.server.ai.teams.DragonAllyService;
import dmr.DragonMounts.server.ai.teams.TeamProvider;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit 5 coverage (commit 21, {@code W8-TEAMS-1}) for the pure, parameter-injected
 * core of {@link DragonAllyService} — no gametest server, no real entities, mirroring
 * {@code DragonWhistleHandlerLogicTests}. {@link DragonAllyService#resolveViaProviders}
 * and {@link DragonAllyService#resolveCached} take only {@code UUID}/{@code
 * List<TeamProvider>}/a nullable {@code MinecraftServer} — no {@code LivingEntity}/{@code
 * Player} construction needed, so the ordering/fail-closed/memoization contract is fully
 * testable here.
 *
 * <p>
 * The {@code isAllied(LivingEntity, LivingEntity)} entry point itself — including the C4
 * parity guarantee ({@code W8-TEAMS-1-C4PARITY}) — needs real {@code Player} objects and
 * so is covered by gametests in {@link DragonTeamPassivityTests} instead: every existing
 * plain-JUnit {@code *LogicTests} class in this repo avoids entity construction, because
 * {@code ./gradlew test} has no Minecraft registry bootstrap available to build one.
 */
public class DragonAllyServiceTests {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static TeamProvider stub(boolean available, boolean teammates) {
        return new TeamProvider() {
            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public boolean areTeammates(MinecraftServer server, UUID a, UUID b) {
                return teammates;
            }
        };
    }

    /** Baseline: no providers at all -&gt; false. */
    @Test
    void resolveViaProvidersWithNoProvidersReturnsFalse() {
        assertFalse(DragonAllyService.resolveViaProviders(List.of(), null, A, B));
    }

    /** Catches: chain not actually consulting an available, teammates-reporting provider. */
    @Test
    void resolveViaProvidersConsultsAnAvailableTruthfulProvider() {
        assertTrue(DragonAllyService.resolveViaProviders(List.of(stub(true, true)), null, A, B));
    }

    /** Catches: chain stopping at the first (unavailable) provider instead of continuing. */
    @Test
    void resolveViaProvidersContinuesPastAnUnavailableProvider() {
        var unavailable = stub(false, true);
        var available = stub(true, true);
        assertTrue(DragonAllyService.resolveViaProviders(List.of(unavailable, available), null, A, B));
    }

    /**
     * Catches: an exception thrown by one provider's {@code areTeammates()} poisoning the
     * whole chain instead of failing closed for just that provider.
     */
    @Test
    void resolveViaProvidersFailsClosedPastAThrowingProvider() {
        TeamProvider throwing = new TeamProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean areTeammates(MinecraftServer server, UUID a, UUID b) {
                throw new RuntimeException("simulated version-skew failure");
            }
        };
        var trueAfter = stub(true, true);
        assertTrue(DragonAllyService.resolveViaProviders(List.of(throwing, trueAfter), null, A, B));
    }

    /** Catches: calling {@code areTeammates()} on a provider whose {@code isAvailable()} reported false. */
    @Test
    void resolveViaProvidersNeverCallsAreTeammatesOnAnUnavailableProvider() {
        TeamProvider mustNotBeCalled = new TeamProvider() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public boolean areTeammates(MinecraftServer server, UUID a, UUID b) {
                throw new AssertionError("areTeammates must not be called when isAvailable() is false");
            }
        };
        assertFalse(DragonAllyService.resolveViaProviders(List.of(mustNotBeCalled), null, A, B));
    }

    /**
     * Catches: the per-tick cache never invalidating (stale results survive into a new
     * tick) or never caching (a perf regression under a busy sensor scan).
     */
    @Test
    void resolveCachedMemoizesWithinATickAndInvalidatesAcrossTicks() {
        var invocations = new AtomicInteger();
        TeamProvider counting = new TeamProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean areTeammates(MinecraftServer server, UUID a, UUID b) {
                invocations.incrementAndGet();
                return true;
            }
        };
        var providers = List.of(counting);

        assertTrue(DragonAllyService.resolveCached(100L, providers, null, A, B));
        assertTrue(DragonAllyService.resolveCached(100L, providers, null, A, B));
        assertEquals(1, invocations.get(), "same tick, same pair must hit the cache on the 2nd call");

        assertTrue(DragonAllyService.resolveCached(101L, providers, null, A, B));
        assertEquals(2, invocations.get(), "a new tick must invalidate the cache and re-consult providers");
    }

    /** The cache key is order-independent: (A, B) and (B, A) must share one cache entry. */
    @Test
    void resolveCachedSharesOneEntryRegardlessOfOperandOrder() {
        var invocations = new AtomicInteger();
        TeamProvider counting = new TeamProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean areTeammates(MinecraftServer server, UUID a, UUID b) {
                invocations.incrementAndGet();
                return true;
            }
        };
        var providers = List.of(counting);

        assertTrue(DragonAllyService.resolveCached(5L, providers, null, A, B));
        assertTrue(DragonAllyService.resolveCached(5L, providers, null, B, A));
        assertEquals(1, invocations.get(), "(A,B) and (B,A) within the same tick must share one cache entry");
    }
}
