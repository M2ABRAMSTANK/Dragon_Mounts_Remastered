package dmr.DragonMounts.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Interface for all network messages in the mod.
 * <p>
 * This interface extends CustomPacketPayload and provides additional methods for packet handling.
 *
 * @param <T> The type of the message
 */
public interface IMessage<T extends CustomPacketPayload> extends CustomPacketPayload {
    /**
     * Gets the stream codec for this message.
     * <p>
     * This method is used by the network system to encode and decode the message.
     *
     * @return The stream codec for this message
     */
    StreamCodec<? super RegistryFriendlyByteBuf, T> streamCodec();

    /**
     * Determines if this message should be automatically synchronized to tracking clients.
     * <p>
     * If this method returns true, the message will be sent to all clients tracking the player
     * who sent the message.
     * <p>
     * <b>WARNING (wave 8, W8-SYNC-1/W8-SYNC-2):</b> the autoSync rebroadcast (PacketHelper's
     * serverbound branch) fires AFTER {@code handle()}/{@code handleServer()} have already run,
     * but it resends the packet's ORIGINAL, pre-handle field values — not anything read back
     * from state {@code handle()}/{@code handleServer()} may have mutated (or refused to
     * mutate). A handler that rejects the action (a failed ownership/ID check, a corrective
     * write elsewhere in the same call) still gets its stale pre-handle payload echoed to
     * trackers-and-self. DragonStatePacket and DismountDragonPacket both shipped with this bug
     * (see their commit history) and both now hardcode {@code autoSync() -> false}, deferring to
     * vanilla's own dirty-flag entity-data sync instead. After those two fixes the only
     * remaining {@code autoSync() == true} packet is {@code CompleteDataSync}, which is {@link
     * #clientboundOnly()} and therefore can never reach this branch — so the autoSync path is
     * effectively dead code today. Think hard before wiring a new packet's autoSync back to
     * true; if you do, make sure it rebroadcasts the ACTUAL post-handle state, not the
     * pre-handle payload.
     *
     * @return True if this message should be automatically synchronized, false otherwise
     */
    default boolean autoSync() {
        return false;
    }

    /**
     * Marks this message as clientbound-only (server-to-client sync packets).
     * <p>
     * All packets are registered playBidirectional, so without this flag a modified
     * client can send any sync packet to the server and have its payload applied to
     * server-side state (R1 in .fork-notes/fix-plan.md — e.g. CompleteDataSync let a
     * client inject arbitrary capability data, including full dragon NBT). When this
     * returns true, {@link PacketHelper} rejects and logs any serverbound delivery.
     *
     * @return True if the server must reject this message when received from a client
     */
    default boolean clientboundOnly() {
        return false;
    }

    /**
     * Handles this message on either the client or server side.
     * <p>
     * This is the main handler method that is called when the message is received.
     *
     * @param context The payload context
     * @param player The player who sent or is receiving the message
     */
    void handle(IPayloadContext context, Player player);

    /**
     * Handles this message on the server side.
     * <p>
     * This method is called when the message is received on the server.
     *
     * @param context The payload context
     * @param player The player who sent the message
     */
    default void handleServer(IPayloadContext context, ServerPlayer player) {}

    /**
     * Handles this message on the client side.
     * <p>
     * This method is called when the message is received on the client.
     *
     * @param context The payload context
     * @param player The player who is receiving the message
     */
    default void handleClient(IPayloadContext context, Player player) {}
}
