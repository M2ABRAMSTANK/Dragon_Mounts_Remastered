package dmr.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dmr.DragonMounts.network.packets.DragonStatePacket;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit 5 coverage for {@link DragonStatePacket} — no gametest server needed for
 * this specific assertion, since {@code autoSync()} is a pure dispatch-metadata getter.
 *
 * <p>
 * W8-SYNC-1: DragonStatePacket became purely serverbound-authoritative in this commit —
 * see {@link dmr.DragonMounts.network.IMessage#autoSync()}'s javadoc for the ordering
 * bug this closes (autoSync rebroadcasts the packet's ORIGINAL pre-handle payload after
 * handle()/handleServer() may have rejected the action). The gametest coverage for the
 * actual handler split (clientbound no-op / serverbound still works) lives in {@link
 * NetworkTests}.
 */
public class DragonStatePacketTests {

    /**
     * Change-detector, not a correctness proof by itself — the actual correctness
     * argument is in {@link dmr.DragonMounts.network.IMessage#autoSync()}'s javadoc.
     * Failure mode: autoSync silently re-enabled, reopening the stale-echo bug.
     */
    @Test
    void autoSyncIsDisabled() {
        assertFalse(new DragonStatePacket(0, 0).autoSync(), "DragonStatePacket.autoSync() must stay false (W8-SYNC-1)");
    }
}
