package dmr.DragonMounts.server.ai.teams;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/**
 * Open Parties and Claims soft-integration. See {@link FtbTeamsProvider}'s javadoc for
 * the integration decision (reflection behind {@link ModList#isLoaded(String)} instead of
 * a {@code compileOnly} dependency) and the deviation to {@link java.lang.reflect.Method}
 * over MethodHandles — both apply identically here, and doubly so: OPAC's coordinate was
 * the one {@code gate-design-teams.json} flagged as a literal unresolved {@code <TODO>} in
 * the original T3-S7 design.
 *
 * <p>
 * Assumed API surface (per {@code design-teams.json} T3-S1, UNVERIFIED — no OPAC jar is
 * present anywhere in this workspace to javap; confirm with a live BMC5 smoke check before
 * relying on this in production; see {@code .fork-notes/wave8/red-baseline.md}):
 *
 * <pre>
 * xaero.pac.common.server.api.OpenPACServerAPI#get(MinecraftServer) -&gt; server API instance
 * (server API instance)#getPartyManager()                            -&gt; party manager
 * (party manager)#getPartyByMember(UUID)                             -&gt; party, or null
 * (party)#getId()                                                    -&gt; some equatable id
 * </pre>
 *
 * Deliberately resolves every hop past the entry point off the ACTUAL runtime object's
 * class rather than a guessed intermediate interface name (e.g. an {@code IPartyManagerAPI}
 * / {@code IPartyAPI} type name), since only the entry class name and the parameter types
 * are stated with any confidence by the design — return types are not, and {@code
 * Class#getMethod} does not need them.
 */
final class OpacProvider implements TeamProvider {

    private static final String MOD_ID = "openpartiesandclaims";
    private static final String API_CLASS = "xaero.pac.common.server.api.OpenPACServerAPI";

    @Override
    public boolean isAvailable() {
        if (!ModList.get().isLoaded(MOD_ID)) return false;
        try {
            Class.forName(API_CLASS).getMethod("get", MinecraftServer.class);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean areTeammates(MinecraftServer server, UUID a, UUID b) {
        try {
            Object api = invokeStatic(API_CLASS, "get", new Class<?>[] {MinecraftServer.class}, server);
            Object partyManager = invokeInstance(api, "getPartyManager");
            Object partyA = invokeInstance(partyManager, "getPartyByMember", new Class<?>[] {UUID.class}, a);
            if (partyA == null) return false;
            Object partyB = invokeInstance(partyManager, "getPartyByMember", new Class<?>[] {UUID.class}, b);
            if (partyB == null) return false;
            Object idA = invokeInstance(partyA, "getId");
            Object idB = invokeInstance(partyB, "getId");
            return idA != null && idA.equals(idB);
        } catch (ReflectiveOperationException e) {
            // Rethrown unchecked so DragonAllyService#resolveViaProviders' fail-closed
            // catch(RuntimeException | LinkageError) still catches it for this provider
            // only, and tries the next.
            throw new IllegalStateException("Open Parties and Claims reflection call failed", e);
        }
    }

    private static Object invokeStatic(String className, String methodName, Class<?>[] paramTypes, Object... args)
            throws ReflectiveOperationException {
        Method method = Class.forName(className).getMethod(methodName, paramTypes);
        return method.invoke(null, args);
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
