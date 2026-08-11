package dmr.DragonMounts.network.packets;

import dmr.DragonMounts.common.capability.DragonOwnerCapability;
import dmr.DragonMounts.network.AbstractMessage;
import dmr.DragonMounts.registry.ModCapabilities;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import lombok.Getter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet for dismounting a dragon.
 */
public class DismountDragonPacket extends AbstractMessage<DismountDragonPacket> {
    private static final StreamCodec<FriendlyByteBuf, DismountDragonPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT,
            DismountDragonPacket::getEntityId,
            ByteBufCodecs.BOOL,
            DismountDragonPacket::isState,
            DismountDragonPacket::new);

    @Getter
    private final int entityId;

    @Getter
    private final boolean state;

    /**
     * Empty constructor for NetworkHandler.
     */
    DismountDragonPacket() {
        this.entityId = -1;
        this.state = false;
    }

    /**
     * Creates a new packet with the given parameters.
     *
     * @param entityId The ID of the entity
     * @param state The state to set
     */
    public DismountDragonPacket(int entityId, boolean state) {
        this.entityId = entityId;
        this.state = state;
    }

    @Override
    protected String getTypeName() {
        return "dismount_dragon";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, DismountDragonPacket> streamCodec() {
        return STREAM_CODEC;
    }

    /**
     * W8-SYNC-2: PacketHelper's serverbound branch runs handle() (which sets
     * cap.shouldDismount then, for state=true, calls player.stopRiding() — which
     * synchronously re-enters via EntityDismountMixin's HEAD inject, flips
     * cap.shouldDismount back to false, and sends an explicit corrective
     * DismountDragonPacket(playerId,false) straight to that player) and only THEN,
     * after handle() returns, rebroadcasts THIS packet's ORIGINAL pre-handle field
     * value (state=true, a final field never re-read from cap) via autoSync. The
     * mixin's correction lands first, the stale autoSync echo lands second, leaving
     * the client permanently shouldDismount=true while the server holds false —
     * which suppresses shift-to-dismount forever via PlayerDismountMixin's
     * wantsToStopRiding() override. Disabling autoSync removes the incorrect second
     * send; the mixin's own explicit, always-correct corrective send is unaffected.
     * See {@link dmr.DragonMounts.network.IMessage#autoSync()}.
     */
    @Override
    public boolean autoSync() {
        return false;
    }

    /**
     * Wave-1 audit (deferred to Wave 4): {@code entityId} is client-supplied and was
     * unverified — a modified client could pass ANY player's entity id and force them
     * to dismount their dragon. Legitimate senders (EntityDismountMixin,
     * KeyInputHandler) always send their own id, so require the target be the sender.
     *
     * <p>W8-SYNC-2: {@code cap.shouldDismount} is now only written when the player is
     * ACTUALLY riding a dragon. Previously this wrote unconditionally — a packet
     * arriving after a server-side dismount (or the dragon's death) could set
     * shouldDismount=true with no dragon left to clear it, and since the field is
     * serialized into the player attachment, vanilla's mount check bounces every
     * later mount attempt with the flag stuck true forever.
     */
    public void handle(IPayloadContext supplier, Player player) {
        if (entityId != player.getId()) {
            return;
        }

        if (player.getControlledVehicle() instanceof TameableDragonEntity) {
            DragonOwnerCapability cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
            cap.shouldDismount = state;
        }

        if (state) {
            player.stopRiding();
        }
    }
}
