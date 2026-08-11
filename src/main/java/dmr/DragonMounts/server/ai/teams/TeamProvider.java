package dmr.DragonMounts.server.ai.teams;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * A single optional-mod ally source consulted by {@link DragonAllyService} after the
 * vanilla scoreboard-team fast path has already missed.
 *
 * <p>
 * Public (rather than package-private as {@code design-teams.json}/{@code
 * gate-design-teams.json} originally specified) because {@code DragonAllyServiceTests}
 * lives in {@code dmr.tests}, not this package — matching the same cross-package
 * necessity that makes {@code DragonWhistleHandler.decideSnapshotRespawn} {@code public}
 * for its own plain-JUnit test class. Hand-rolled stub implementations of this interface
 * (no Mockito, per repo convention) are how both {@code DragonAllyServiceTests} and the
 * teams gametests exercise the ordering/fail-closed/memoization behavior without a real
 * FTB Teams or Open Parties and Claims install.
 */
public interface TeamProvider {

    /**
     * Cheap, side-effect-free liveness check. Must never throw in a well-behaved
     * implementation, but {@link DragonAllyService} wraps every call in a
     * {@code catch (RuntimeException | LinkageError)} fail-closed guard regardless, since
     * a version-skewed optional dependency is exactly the case this exists to survive.
     */
    boolean isAvailable();

    /**
     * Only ever invoked by {@link DragonAllyService} when this provider's own {@link
     * #isAvailable()} most recently returned {@code true}. {@code server} may be used to
     * resolve the mod's own manager/API instance; implementations must not cache it
     * beyond the call.
     */
    boolean areTeammates(MinecraftServer server, UUID a, UUID b);
}
