package dmr.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dmr.DragonMounts.network.packets.DragonCommandPacket.Command;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit unit tests (W8-SUMMON-3) for {@link Command#resolveCommand(int)} — the
 * id-keyed lookup that replaced {@code Command.values()[command]} ordinal indexing on
 * {@code command}, which is unvalidated client input (default -1 on the packet's
 * no-arg constructor; reachable from a modified client). Mirrors
 * DragonWhistleHandlerLogicTests.java's established pattern — package {@code dmr.tests},
 * no gametest server, since {@code resolveCommand} takes its input as a plain
 * parameter.
 */
public class DragonCommandPacketLogicTests {

    /**
     * Catches any future enum insertion/reorder that breaks the id&lt;-&gt;ordinal
     * coincidence the old {@code values()[command]} code silently relied on — every
     * declared {@link Command} must resolve from its own id.
     */
    @Test
    void resolveCommandMatchesEveryDeclaredId() {
        for (Command cmd : Command.values()) {
            assertEquals(cmd, Command.resolveCommand(cmd.id), "resolveCommand must round-trip " + cmd + "'s own id");
        }
    }

    /**
     * The AIOOBE constraint-a3 flagged: an out-of-range or malformed {@code command}
     * int (including the no-arg constructor's -1 default) must resolve to {@code null}
     * rather than throw.
     */
    @Test
    void resolveCommandRejectsOutOfRangeId() {
        assertNull(Command.resolveCommand(-1));
        assertNull(Command.resolveCommand(99));
    }
}
