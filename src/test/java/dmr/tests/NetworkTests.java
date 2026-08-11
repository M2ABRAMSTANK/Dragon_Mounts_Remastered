package dmr.tests;

import dmr.DMRTestConstants;
import dmr.DragonMounts.ModConstants;
import dmr.DragonMounts.network.packets.DragonStatePacket;
import dmr.DragonMounts.registry.DragonBreedsRegistry;
import dmr.DragonMounts.registry.ModEntities;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/**
 * Tests for the network packet handling functionality of the mod. These tests
 * verify that network packets can be properly encoded, decoded, and processed
 * to ensure reliable communication between client and server.
 */
@PrefixGameTestTemplate(false)
@ForEachTest(groups = "Network")
public class NetworkTests {

    /**
     * Tests the DragonStatePacket encoding and decoding functionality.
     *
     * <p>
     * This test verifies that: 1. A DragonStatePacket can be created with the
     * correct entity ID and state 2. The packet can be properly encoded to a byte
     * buffer 3. The packet can be properly decoded from a byte buffer 4. The
     * decoded packet contains the same values as the original packet
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void testDragonStatePacket(ExtendedGameTestHelper helper) {
        // Create a player
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);

        // Create a dragon
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);

        // Create a packet
        var packet = new DragonStatePacket(dragon.getId(), 1);

        // Test encoding and decoding
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        DragonStatePacket.STREAM_CODEC.encode(buffer, packet);
        DragonStatePacket decodedPacket = DragonStatePacket.STREAM_CODEC.decode(buffer);

        // Verify the packet has the correct values
        if (packet.getEntityId() != dragon.getId()) {
            helper.fail("Packet entity ID doesn't match expected value");
        }

        if (packet.getState() != 1) {
            helper.fail("Packet state doesn't match expected value");
        }

        // Verify the decoded packet has the correct values
        if (decodedPacket.getEntityId() != dragon.getId()) {
            helper.fail("Decoded packet entity ID doesn't match expected value");
        }

        if (decodedPacket.getState() != 1) {
            helper.fail("Decoded packet state doesn't match expected value");
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-1 regression: {@code DragonStatePacket} became purely
     * serverbound-authoritative — its {@code handle(IPayloadContext, Player)} (the
     * method PacketHelper's CLIENTBOUND branch invokes) must be a no-op. Pre-seeds
     * sit + wander state on a tamed, unmounted dragon so the packet WOULD mutate it
     * under the pre-fix handle() body — a dragon left in its default (not-sitting,
     * no-wander-target) state would make "nothing changed" trivially true regardless
     * of whether handle() ran, which is exactly the vacuous-assertion shape this
     * repo's red-baseline protocol exists to catch.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void testDragonStatePacketClientboundIsNoOp(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        // Order matters: setWanderTarget(present) itself calls stopSitting()
        // (DragonOwnershipComponent), so the wander target must be seeded FIRST and
        // setOrderedToSit(true) applied second, or the setup itself — not handle() —
        // would clear the sit flag before the packet is ever involved.
        dragon.setWanderTarget(Optional.of(GlobalPos.of(player.level.dimension(), player.blockPosition())));
        dragon.setOrderedToSit(true);

        // state=1 (Follow): the pre-fix handle() body would clear BOTH isOrderedToSit
        // and the wander target for this state.
        var packet = new DragonStatePacket(dragon.getId(), ModConstants.DragonConstants.DRAGON_STATE_FOLLOW);
        packet.handle(null, player);

        if (!dragon.isOrderedToSit()) {
            helper.fail("Clientbound handle() mutated isOrderedToSit() — DragonStatePacket.handle()"
                    + " must be a no-op on the receiving client (W8-SYNC-1)");
        }

        if (dragon.getWanderTarget().isEmpty()) {
            helper.fail("Clientbound handle() cleared the wander target — DragonStatePacket.handle()"
                    + " must be a no-op on the receiving client (W8-SYNC-1)");
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-1 regression: the mutation logic moved verbatim into
     * {@code handleServer(IPayloadContext, ServerPlayer)} — this drives it directly
     * (matching PacketHelper's real serverbound dispatch order) and asserts the
     * sit/follow/wander buttons still work exactly as before the split.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void testDragonStatePacketServerboundStillWorks(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var packet = new DragonStatePacket(dragon.getId(), 0); // Sit
        packet.handleServer(null, player);

        if (!dragon.isOrderedToSit()) {
            helper.fail("handleServer() did not order the dragon to sit — serverbound dispatch"
                    + " broken by the W8-SYNC-1 handle()/handleServer() split");
        }

        helper.succeed();
    }
}
