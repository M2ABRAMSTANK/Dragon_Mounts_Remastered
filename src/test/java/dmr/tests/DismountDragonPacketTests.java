package dmr.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dmr.DragonMounts.network.packets.DismountDragonPacket;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit 5 coverage for {@link DismountDragonPacket} — no gametest server needed
 * for this specific assertion, since {@code autoSync()} is a pure dispatch-metadata
 * getter.
 *
 * <p>
 * W8-SYNC-2: {@code autoSync()} rebroadcast this packet's ORIGINAL pre-handle payload
 * (state=true) AFTER {@code EntityDismountMixin}'s HEAD inject had already sent an
 * explicit, always-correct corrective {@code DismountDragonPacket(playerId,false)} to
 * the same player — the stale echo landed second and left the client permanently
 * {@code shouldDismount=true} while the server held {@code false}. See {@link
 * dmr.DragonMounts.network.IMessage#autoSync()}'s javadoc for the full ordering
 * argument (shared with {@link dmr.DragonMounts.network.packets.DragonStatePacket}'s
 * identical bug class, fixed the commit before this one). The gametest coverage for
 * the adjacent persisted-flag hazard this commit also closes lives in {@link
 * DragonTests#dismountFlagNotSetForPlayerNoLongerControllingADragon}.
 *
 * <p>
 * <b>Dropped per the amended spec (W8-SYNC-2 / integration-plan.json commit 11):</b> a
 * {@code PacketHelperOrderingTests.testMixinCorrectionPrecedesAnyAutoSync} test that
 * would capture the sequence of packets sent to the acting player's real Netty
 * connection was scoped out unless a concrete {@code ChannelOutboundHandlerAdapter}
 * seam exists on this repo's mock server player. It does not — {@code
 * ExtendedGameTestHelper#makeTickingMockServerPlayerInLevel} wires a functional but
 * non-instrumented {@code Connection}, and standing up a real Netty pipeline tap for
 * one test would be disproportionate scaffolding for what the {@code autoSync()}
 * boolean already fully determines (see {@code PacketHelper#handlePacket}: the
 * autoSync rebroadcast is a single unconditional {@code if (message.autoSync())} gate
 * with no other path to a second send). Recorded here rather than shipped as an
 * unimplementable or vacuous test, per the DEVIATION DOCTRINE.
 */
public class DismountDragonPacketTests {

    /**
     * Change-detector, not a correctness proof by itself — the actual correctness
     * argument is in {@link dmr.DragonMounts.network.IMessage#autoSync()}'s javadoc.
     * Failure mode: autoSync silently re-enabled, reopening the stale-echo bug.
     */
    @Test
    void autoSyncIsDisabled() {
        assertFalse(
                new DismountDragonPacket(0, true).autoSync(), "DismountDragonPacket.autoSync() must stay false (W8-SYNC-2)");
    }
}
