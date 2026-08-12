package dmr.DragonMounts.server.ai.teams;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.config.ServerConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Shared ally-decision engine for the dragon-passivity-toward-teammates feature
 * (operator task #3 / {@code W8-TEAMS-*}): vanilla scoreboard Team, then (if enabled)
 * FTB Teams, then Open Parties and Claims, then false.
 *
 * <p>
 * <b>Ordering is the whole C4 argument.</b> {@link #isAllied(LivingEntity, LivingEntity)}
 * ALWAYS evaluates the vanilla {@code a.isAlliedTo(b)} fast path first and short-circuits
 * true — it never even reads {@link ServerConfig#DRAGON_TEAM_PASSIVITY} or touches a
 * provider for a pair vanilla already recognizes as allied. That single ordering choice is
 * what makes every call-site swap in commits 22/23 provably byte-identical to the
 * pre-existing {@code isAlliedTo} call it replaces for any pair neither FTB Teams nor OPAC
 * recognizes as teamed — see {@code W8-TEAMS-1-C4PARITY} in {@code
 * DragonTeamPassivityTests}, the artifact that discharges C4 for this whole area.
 *
 * <p>
 * <b>One real no-mod delta: same-owner pet-vs-pet.</b> {@link #isTeammateOfOwner} calls
 * {@code isAllied(candidate, owner)} — candidate FIRST — so its vanilla fast path evaluates
 * {@code candidate.isAlliedTo(owner)}, not {@code dragon.isAlliedTo(candidate)}. When
 * {@code candidate} is itself a tamed pet of the SAME owner (owner present in the dragon's
 * level), {@code TamableAnimal#isAlliedTo}'s owner-delegation hits its {@code entity ==
 * livingentity} case directly ({@code candidate}'s own owner IS the entity being compared
 * against) and returns {@code true} unconditionally — this is the vanilla fast path in
 * {@link #isAllied}, so {@link ServerConfig#DRAGON_TEAM_PASSIVITY} is never even read and
 * no provider is touched; the behavior is NOT controllable via that flag. The pre-existing
 * call this replaced, a raw {@code dragon.isAlliedTo(candidate)} (see {@code
 * Sensor#isEntityAttackable}, attacker-first), is asymmetric the other way (owner-to-pet,
 * not pet-to-pet) and returns {@code false} for that same pair absent a shared scoreboard
 * team. Net effect: a tamed dragon calling into {@link #isTeammateOfOwner} (currently:
 * {@code maybeRetaliate} in {@code DragonAI}, and {@code wantsToAttack} in {@code
 * DragonCombatComponent}) no longer retaliates against / assists against another pet owned
 * by the same player WHILE THE OWNER IS ONLINE, where it previously did — this is a genuine,
 * currently config-unconditional behavior change, not merely the FTB Teams/Open Parties and
 * Claims delta the two call sites' own javadocs otherwise describe. (The rarer
 * offline/cross-dimension-owner branch of {@link #isTeammateOfOwner} routes through {@link
 * #isAlliedByUuid} instead, which DOES require {@code DRAGON_TEAM_PASSIVITY} plus an
 * available provider for the equivalent same-UUID pairing.)
 *
 * <p>
 * <b>Pet delegation.</b> Mirrors vanilla {@code TamableAnimal#isAlliedTo}'s own owner
 * delegation: a tamed pet normalizes to its owner's identity on either side of the
 * comparison, so a teammate's tamed dragon reads as allied via this path too — closing
 * {@code investigate-compat.json constraint-e2}'s explicitly stated "the dragon will
 * attack a teammate's summoned dragon" symptom, which a Player-only comparison would have
 * left strictly unfixed relative to what vanilla already does today.
 *
 * <p>
 * <b>Owner resolution.</b> {@link #isTeammateOfOwner(TamableAnimal, LivingEntity)}
 * resolves the dragon's owner by {@code getOwnerUUID()} for the mod-provider path, NOT by
 * the same-level-only {@code getOwner()} scan ({@code DragonOwnershipComponent#getOwner}
 * only searches {@code dragon.level().players()}) — so passivity (and, as a side effect,
 * vanilla scoreboard-team protection routed through this method) survives an offline or
 * cross-dimension owner, exactly the case where an unattended dragon is most likely to
 * meet a teammate.
 *
 * <p>
 * <b>Single-threaded by construction.</b> Dragon AI, {@code hurt()}, and packet handlers
 * all run on the server main thread. The static tick cache below is a plain, non-
 * synchronized {@link HashMap} on that assumption. {@link #isAllied} enforces it instead
 * of merely asserting it: {@link LivingEntity#getServer()} degrades to {@code null} on a
 * client level (the base {@code Level#getServer()} returns {@code null}; only {@code
 * ServerLevel} overrides it), so a client-thread call on an integrated server falls
 * through to "not allied via mods" rather than ever touching the cache.
 */
public final class DragonAllyService {

    private DragonAllyService() {}

    /**
     * Public (not package-private as originally specified) so {@code
     * DragonAllyServiceTests}/{@code DragonTeamPassivityTests} (package {@code dmr.tests})
     * can install stub providers — see {@link TeamProvider}'s javadoc for why this whole
     * seam had to move from package-private to public.
     */
    public static volatile List<TeamProvider> providers = List.of(new FtbTeamsProvider(), new OpacProvider());

    private static long cachedTick = Long.MIN_VALUE;
    private static final Map<PairKey, Boolean> tickCache = new HashMap<>();

    private static volatile boolean availabilityLogged = false;

    /**
     * True iff {@code a} and {@code b} are vanilla-scoreboard allies (unconditionally,
     * matching current {@code isAlliedTo} semantics exactly), OR ({@link
     * ServerConfig#DRAGON_TEAM_PASSIVITY} is on AND both normalize to a Player/pet-owner
     * UUID AND FTB Teams or Open Parties and Claims reports those UUIDs teamed).
     */
    public static boolean isAllied(@Nullable LivingEntity a, @Nullable LivingEntity b) {
        if (a == null || b == null) return false;
        // Vanilla fast path — ALWAYS evaluated first, never touches config or providers.
        // This is what makes every wire-in call-site swap provably C4-identical.
        if (a.isAlliedTo(b)) return true;
        return isAlliedViaMods(a, b);
    }

    /**
     * Convenience wrapper for the common "is this candidate a teammate of this tamed
     * dragon's owner" question, mirroring vanilla {@code TamableAnimal#isAlliedTo}'s own
     * delegation shape. False if the dragon is untamed or has no recorded owner. Resolves
     * the owner by UUID for the mod-provider fallback so an offline/cross-dimension owner
     * doesn't silently disable passivity — see this class's javadoc.
     */
    public static boolean isTeammateOfOwner(@Nullable TamableAnimal dragon, @Nullable LivingEntity candidate) {
        if (dragon == null || candidate == null || !dragon.isTame()) return false;
        UUID ownerUuid = dragon.getOwnerUUID();
        if (ownerUuid == null) return false;

        // Owner is present in the dragon's own level: run the full isAllied pipeline
        // (vanilla fast path included) against the real owner entity.
        LivingEntity owner = dragon.getOwner();
        if (owner != null) {
            return isAllied(candidate, owner);
        }

        // Owner offline or in another dimension: DragonOwnershipComponent#getOwner()
        // cannot see them, and vanilla TamableAnimal#getTeam()/#isAlliedTo() both fall
        // through to the dragon's own (nonexistent) team in this case too — so there is no
        // vanilla fast path available. Go straight to the UUID-keyed mod provider chain.
        UUID candidateUuid = allyUuidOf(candidate);
        return isAlliedByUuid(dragon, candidateUuid, ownerUuid);
    }

    private static boolean isAlliedViaMods(LivingEntity a, LivingEntity b) {
        if (!ServerConfig.DRAGON_TEAM_PASSIVITY) return false;
        UUID uuidA = allyUuidOf(a);
        UUID uuidB = allyUuidOf(b);
        return isAlliedByUuid(a, uuidA, uuidB);
    }

    private static boolean isAlliedByUuid(LivingEntity serverSource, @Nullable UUID a, @Nullable UUID b) {
        if (!ServerConfig.DRAGON_TEAM_PASSIVITY) return false;
        if (a == null || b == null) return false;

        MinecraftServer server = serverSource.getServer();
        if (server == null) return false;

        logAvailabilityOnce();

        // "Same normalized owner" (e.g. (owner, ownedDragon) in that operand order, or two
        // of the same owner's pets) only short-circuits to true when a mod provider is
        // actually available. With NO team mod installed — the shipping default, and the
        // configuration W8-TEAMS-1-C4PARITY's providers-empty condition models — this must
        // fall all the way back to vanilla, which does NOT recognize that pairing on its
        // own (TamableAnimal#isAlliedTo's owner delegation is one-directional: a pet reads
        // as allied to its owner, but Player#isAlliedTo has no reciprocal pet-ownership
        // awareness). Gating on provider availability, not merely a non-empty #providers
        // list, matters for the real (non-test) no-mod-installed case too: the production
        // #providers list is never empty (FtbTeamsProvider/OpacProvider are always
        // installed), only unavailable.
        if (a.equals(b)) {
            return anyProviderAvailable();
        }

        return resolveCached(server.getTickCount(), providers, server, a, b);
    }

    /** Fail-closed liveness scan mirroring {@link #logAvailabilityOnce()}'s own guard. */
    private static boolean anyProviderAvailable() {
        for (TeamProvider provider : providers) {
            try {
                if (provider.isAvailable()) return true;
            } catch (RuntimeException | LinkageError e) {
                // fail closed for this provider only; keep scanning the rest
            }
        }
        return false;
    }

    /**
     * Normalizes an entity to the UUID that should stand in for it in an ally comparison:
     * a Player's own UUID, or — mirroring vanilla {@code TamableAnimal#isAlliedTo}'s owner
     * delegation — a tamed pet's owner UUID. Anything else (wild mobs, untamed animals)
     * cannot participate in the mod-provider path and normalizes to {@code null}.
     */
    @Nullable private static UUID allyUuidOf(LivingEntity entity) {
        if (entity instanceof Player player) return player.getUUID();
        if (entity instanceof TamableAnimal pet && pet.isTame()) return pet.getOwnerUUID();
        return null;
    }

    private static void logAvailabilityOnce() {
        if (availabilityLogged) return;
        availabilityLogged = true;
        var available = providers.stream()
                .filter(p -> {
                    try {
                        return p.isAvailable();
                    } catch (RuntimeException | LinkageError e) {
                        return false;
                    }
                })
                .map(p -> p.getClass().getSimpleName())
                .toList();
        DMR.LOGGER.info(
                "DragonAllyService: team providers available at first use: {}",
                available.isEmpty() ? "none" : available);
    }

    /**
     * Pure, parameter-injected memoization core so unit tests can drive tick invalidation
     * without constructing a {@link MinecraftServer} (stub providers must not dereference
     * {@code server} — tests pass {@code null}). Production calls this with {@code
     * server.getTickCount()} and the live {@link #providers} list.
     */
    public static boolean resolveCached(
            long tick, List<TeamProvider> providers, @Nullable MinecraftServer server, UUID a, UUID b) {
        if (tick != cachedTick) {
            tickCache.clear();
            cachedTick = tick;
        }

        PairKey key = PairKey.of(a, b);
        Boolean cached = tickCache.get(key);
        if (cached != null) return cached;

        boolean result = resolveViaProviders(providers, server, a, b);
        tickCache.put(key, result);
        return result;
    }

    /**
     * Pure iteration over the provider chain in order, fail-closed per provider: an
     * unavailable provider's {@code areTeammates} is never called, and an exception from
     * either method only rules out that one provider — the remaining providers are still
     * tried, and a provider throwing never propagates out to poison dragon AI.
     */
    public static boolean resolveViaProviders(
            List<TeamProvider> providers, @Nullable MinecraftServer server, UUID a, UUID b) {
        for (TeamProvider provider : providers) {
            boolean available;
            try {
                available = provider.isAvailable();
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            if (!available) continue;

            try {
                if (provider.areTeammates(server, a, b)) return true;
            } catch (RuntimeException | LinkageError e) {
                // fail closed for this provider only; try the next
            }
        }
        return false;
    }

    /**
     * Test seam: install a stub provider list and clear the tick cache. Clearing here (not
     * just on tick change) matters because a stub installed mid-tick would otherwise return
     * whatever the PREVIOUS providers' answer for that same tick already cached — silently
     * testing nothing. Callers must restore in a {@code finally} block; {@link #providers}
     * is static and shared across the whole gametest JVM run.
     */
    public static void setProvidersForTest(List<TeamProvider> testProviders) {
        providers = testProviders;
        tickCache.clear();
        cachedTick = Long.MIN_VALUE;
    }

    /** Test seam: restore the real provider chain and clear the tick cache. */
    public static void resetProviders() {
        providers = List.of(new FtbTeamsProvider(), new OpacProvider());
        tickCache.clear();
        cachedTick = Long.MIN_VALUE;
    }

    /** Order-normalized UUID pair so (owner, candidate) and (candidate, owner) share one cache entry. */
    private record PairKey(UUID lo, UUID hi) {
        static PairKey of(UUID a, UUID b) {
            return a.compareTo(b) <= 0 ? new PairKey(a, b) : new PairKey(b, a);
        }
    }
}
