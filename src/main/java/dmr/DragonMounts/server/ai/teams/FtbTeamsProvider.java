package dmr.DragonMounts.server.ai.teams;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/**
 * FTB Teams soft-integration.
 *
 * <p>
 * <b>Integration decision (commit 21, overriding {@code T3-S7}):</b> resolved via
 * reflection gated behind {@link ModList#isLoaded(String)} instead of a {@code
 * compileOnly} Maven dependency. {@code gate-design-teams.json}'s verdict on T3-S7 found
 * the design's own artifact coordinate unresolved (a literal {@code <TODO>}) and the
 * javap verification behind it unreproducible in this workspace (no FTB Teams jar present
 * anywhere on this machine to check against) — pinning an unverifiable coordinate would
 * make {@code compileJava} depend on a new Maven repository actually being reachable for
 * the WHOLE mod to build, not just this optional integration. Reflection fails closed by
 * construction and needs no such pin.
 *
 * <p>
 * <b>Deviation from the plan's literal "cached MethodHandles":</b> implemented with {@link
 * java.lang.reflect.Method} instead. {@code MethodHandles.Lookup.findVirtual} requires the
 * EXACT return type in its {@code MethodType} to resolve a method; since this API surface
 * is itself unverified here (no jar to javap), guessing a return type wrong would make
 * resolution silently and permanently fail even on a server where FTB Teams IS installed
 * and loaded — defeating the entire point of the integration. {@code
 * Class#getMethod(String, Class...)} only needs exact PARAMETER types (which {@code
 * design-teams.json} does specify), so it resolves correctly regardless of an unknown
 * return type. Each hop below is also resolved off the ACTUAL runtime object's class
 * rather than a pre-guessed intermediate interface name, for the same reason. Same
 * contract otherwise: {@link ModList#isLoaded(String)} gate, fail-closed on any exception,
 * called at most once per (owner, candidate) pair per server tick because {@link
 * DragonAllyService#resolveCached} memoizes above this layer.
 *
 * <p>
 * Assumed API surface (per {@code design-teams.json} T3-S1, UNVERIFIED — confirm with a
 * real {@code javap} pass and a live BMC5 smoke check before relying on this in
 * production; see {@code .fork-notes/wave8/red-baseline.md}):
 *
 * <pre>
 * dev.ftb.mods.ftbteams.api.FTBTeamsAPI#api()               -&gt; FTBTeamsAPI instance
 * FTBTeamsAPI#isManagerLoaded()                              -&gt; boolean
 * FTBTeamsAPI#getManager()                                   -&gt; TeamManager
 * TeamManager#arePlayersInSameTeam(UUID, UUID)                -&gt; boolean
 * </pre>
 */
final class FtbTeamsProvider implements TeamProvider {

    private static final String MOD_ID = "ftbteams";
    private static final String API_CLASS = "dev.ftb.mods.ftbteams.api.FTBTeamsAPI";

    @Override
    public boolean isAvailable() {
        if (!ModList.get().isLoaded(MOD_ID)) return false;
        try {
            Object api = invokeStatic(API_CLASS, "api");
            return (boolean) invokeInstance(api, "isManagerLoaded");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean areTeammates(MinecraftServer server, UUID a, UUID b) {
        try {
            Object api = invokeStatic(API_CLASS, "api");
            Object manager = invokeInstance(api, "getManager");
            return (boolean)
                    invokeInstance(manager, "arePlayersInSameTeam", new Class<?>[] {UUID.class, UUID.class}, a, b);
        } catch (ReflectiveOperationException e) {
            // Rethrown unchecked so DragonAllyService#resolveViaProviders' fail-closed
            // catch(RuntimeException | LinkageError) still catches it for this provider
            // only, and tries the next.
            throw new IllegalStateException("FTB Teams reflection call failed", e);
        }
    }

    private static Object invokeStatic(String className, String methodName) throws ReflectiveOperationException {
        Method method = Class.forName(className).getMethod(methodName);
        return method.invoke(null);
    }

    private static Object invokeInstance(Object target, String methodName) throws ReflectiveOperationException {
        return invokeInstance(target, methodName, new Class<?>[0]);
    }

    private static Object invokeInstance(Object target, String methodName, Class<?>[] paramTypes, Object... args)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName, paramTypes);
        return method.invoke(target, args);
    }
}
