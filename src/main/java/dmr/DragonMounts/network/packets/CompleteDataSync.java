package dmr.DragonMounts.network.packets;

import dmr.DragonMounts.network.AbstractMessage;
import dmr.DragonMounts.registry.ModCapabilities;
import dmr.DragonMounts.util.PlayerStateUtils;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class CompleteDataSync extends AbstractMessage<CompleteDataSync> {
    private static final StreamCodec<FriendlyByteBuf, CompleteDataSync> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT,
            CompleteDataSync::getPlayerId,
            ByteBufCodecs.COMPOUND_TAG,
            CompleteDataSync::getTag,
            CompleteDataSync::new);

    @Getter
    private final int playerId;

    @Getter
    private final CompoundTag tag;

    /**
     * Empty constructor for NetworkHandler.
     */
    CompleteDataSync() {
        this.playerId = -1;
        this.tag = new CompoundTag();
    }

    /**
     * Creates a new packet with the given parameters.
     *
     * @param playerId The ID of the player
     * @param tag The data tag
     */
    public CompleteDataSync(int playerId, CompoundTag tag) {
        this.playerId = playerId;
        this.tag = tag;
    }

    /**
     * Creates a new packet for the given player.
     *
     * @param player The player
     */
    public CompleteDataSync(Player player) {
        this(
                player.getId(),
                player.getData(ModCapabilities.PLAYER_CAPABILITY).serializeNBT(player.level.registryAccess()));
    }

    @Override
    protected String getTypeName() {
        return "complete_data_sync";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, CompleteDataSync> streamCodec() {
        return STREAM_CODEC;
    }

    @Override
    public boolean autoSync() {
        return true;
    }

    /**
     * R1 (security): CompleteDataSync is a server-to-client capability sync. autoSync +
     * playBidirectional meant a modified client could send one serverbound and inject
     * arbitrary capability data (including full dragon NBT snapshots) that was then
     * rebroadcast to nearby clients. PacketHelper rejects and logs serverbound deliveries.
     */
    @Override
    public boolean clientboundOnly() {
        return true;
    }

    /**
     * Pure routing decision for an incoming capability payload: should a payload
     * carrying {@code payloadPlayerId} be applied to the local player
     * {@code localPlayerId}?
     *
     * <p>Extracted (behavior-preserving) so the routing rule is testable outside a
     * client. The CURRENT behavior applies every received payload to the local player,
     * ignoring the payload's player id entirely — this is the #88/#113 cross-player
     * whistle-binding corruption vector. Wave 4 changes this to
     * {@code payloadPlayerId == localPlayerId} (see .fork-notes/fix-plan.md).
     */
    public static boolean shouldApplyToLocalPlayer(int payloadPlayerId, int localPlayerId) {
        return true;
    }

    @Override
    public void handle(IPayloadContext supplier, Player player) {
        if (!shouldApplyToLocalPlayer(playerId, player.getId())) {
            return;
        }
        PlayerStateUtils.getHandler(player).deserializeNBT(player.level.registryAccess(), tag);
        player.refreshDimensions();
    }
}
