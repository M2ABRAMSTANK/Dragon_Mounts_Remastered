package dmr.DragonMounts.network.packets;

import dmr.DragonMounts.network.AbstractMessage;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import java.util.Optional;
import lombok.Getter;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class DragonStatePacket extends AbstractMessage<DragonStatePacket> {
    public static final StreamCodec<FriendlyByteBuf, DragonStatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT,
            DragonStatePacket::getEntityId,
            ByteBufCodecs.INT,
            DragonStatePacket::getState,
            DragonStatePacket::new);

    @Getter
    private final int entityId;

    @Getter
    private final int state;

    /**
     * Empty constructor for NetworkHandler.
     */
    DragonStatePacket() {
        this.entityId = -1;
        this.state = -1;
    }

    /**
     * Creates a new packet with the given parameters.
     *
     * @param entityId The ID of the entity
     * @param state The state to set
     */
    public DragonStatePacket(int entityId, int state) {
        this.entityId = entityId;
        this.state = state;
    }

    @Override
    protected String getTypeName() {
        return "dragon_state";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, DragonStatePacket> streamCodec() {
        return STREAM_CODEC;
    }

    /**
     * W8-SYNC-1: the mutation this packet performs is a SynchedEntityData write
     * (setOrderedToSit/setWanderTarget) already broadcast to every tracking client by
     * vanilla's own dirty-flag ServerEntity#sendChanges() — autoSync was a redundant
     * echo that rebroadcast the packet's ORIGINAL pre-handle field values even when
     * handleServer's isTamedFor/controllingPassenger gate silently rejected the
     * action. See {@link dmr.DragonMounts.network.IMessage#autoSync()}.
     */
    @Override
    public boolean autoSync() {
        return false;
    }

    /**
     * W8-SYNC-1: DragonStatePacket is now purely serverbound-authoritative — all
     * mutation lives in {@link #handleServer(IPayloadContext, ServerPlayer)}, which
     * PacketHelper's serverbound branch invokes after this no-op. The clientbound
     * flow (PacketHelper's isClientbound branch calls handle() then handleClient())
     * must never mutate local dragon state from a received DragonStatePacket — the
     * two clientbound sends that remain in DragonWhistleHandler
     * (summonExistingDragon / respawnDragonFromSnapshot) are now INERT on wave-8+
     * clients and are retained ONLY so .2/.3 clients keep their local echo (C6); see
     * the comments at those two call sites. Deliberately empty — do not add logic
     * here.
     */
    @Override
    public void handle(IPayloadContext supplier, Player player) {}

    /**
     * Wave-1 audit (deferred to Wave 4): the dragon id is client-supplied and
     * unverified — without an ownership check, any client can sit/follow/wander ANY
     * dragon by guessing its entity id. Require the sender own (or be tamed-for) the
     * dragon before applying the state change.
     *
     * <p>W8-SYNC-1: moved verbatim out of {@link #handle(IPayloadContext, Player)} —
     * this packet is dispatched only on the server now.
     */
    @Override
    public void handleServer(IPayloadContext context, ServerPlayer player) {
        var level = player.level;
        var entity = level.getEntity(getEntityId());

        if (entity instanceof TameableDragonEntity dragon
                && dragon.isTamedFor(player)
                && dragon.getControllingPassenger() == null) {
            switch (getState()) {
                case 0 -> { // Sit
                    dragon.setWanderTarget(Optional.empty());
                    dragon.setOrderedToSit(true);
                }
                case 1 -> { // Follow
                    dragon.setOrderedToSit(false);
                    dragon.setWanderTarget(Optional.empty());
                }
                case 2 -> { // Wander
                    dragon.setOrderedToSit(false);
                    dragon.setWanderTarget(Optional.of(GlobalPos.of(level.dimension(), player.blockPosition())));
                }
            }
        }
    }
}
