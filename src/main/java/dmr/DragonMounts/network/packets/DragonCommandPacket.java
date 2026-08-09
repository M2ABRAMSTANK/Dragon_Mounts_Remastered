package dmr.DragonMounts.network.packets;

import static dmr.DragonMounts.server.entity.DragonAgroState.*;

import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.network.AbstractMessage;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.util.PlayerStateUtils;
import java.util.Optional;
import lombok.Getter;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet for sending dragon commands from the client to the server.
 */
public class DragonCommandPacket extends AbstractMessage<DragonCommandPacket> {
    private static final StreamCodec<FriendlyByteBuf, DragonCommandPacket> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.INT, DragonCommandPacket::getCommand, DragonCommandPacket::new);

    @Getter
    private final int command;

    /**
     * Empty constructor for NetworkHandler.
     */
    DragonCommandPacket() {
        this.command = -1;
    }

    /**
     * Creates a new packet with the given command.
     *
     * @param command The command ID
     */
    public DragonCommandPacket(int command) {
        this.command = command;
    }

    /**
     * Creates a new DragonCommandPacket with the given command.
     *
     * @param command The command to send
     */
    public DragonCommandPacket(Command command) {
        this(command.id);
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, DragonCommandPacket> streamCodec() {
        return STREAM_CODEC;
    }

    @Override
    protected String getTypeName() {
        return "dragon_command";
    }

    /**
     * Enum of possible dragon commands.
     */
    public enum Command {
        SIT(0),
        FOLLOW(1),
        WANDER(2),
        WHISTLE(3),
        PASSIVE(4),
        NEUTRAL(5),
        AGGRESSIVE(6);

        public final int id;

        Command(int id) {
            this.id = id;
        }
    }

    @Override
    public void handle(IPayloadContext context, Player player) {}

    @Override
    public void handleServer(IPayloadContext context, ServerPlayer player) {
        var level = (ServerLevel) player.level;

        var whistleItem = DragonWhistleHandler.getDragonWhistleItem(player);

        if (whistleItem == null) {
            // Wave 5, Fix A3: this bare return previously left the player with no
            // feedback at all — reuses the existing whistle-summon lang key.
            player.displayClientMessage(
                    Component.translatable("dmr.dragon_call.no_whistle").withStyle(ChatFormatting.RED), true);
            return;
        }

        var index = whistleItem.getColor().getId();
        var state = PlayerStateUtils.getHandler(player);
        var instance = state.dragonInstances.get(index);
        if (instance == null) {
            // Wave 5, Fix A3: ditto — same bare-return silence.
            player.displayClientMessage(
                    Component.translatable("dmr.dragon_call.nodragon").withStyle(ChatFormatting.RED), true);
            return;
        }

        var cmd = Command.values()[command];
        if (cmd == Command.WHISTLE) {
            // Wave 5, Fix A2: only send the "whistle" action-bar on an actual success.
            // Both this and any failure message summonDragon sends land in the same
            // tick, and the client only renders the LAST one it receives — sending this
            // unconditionally used to always win, silently swallowing nospace/nodragon/
            // riding/respawn/on_cooldown/not_found/teleport_blocked on this (radial-menu)
            // path.
            if (DragonWhistleHandler.summonDragon(player)) {
                player.displayClientMessage(Component.translatable("dmr.command_mode.whistle.text"), true);
            }
            return;
        }

        var dragonEntity = level.getEntity(instance.getEntityId());

        if (dragonEntity instanceof TameableDragonEntity dragon) {
            switch (cmd) {
                case SIT -> {
                    dragon.setWanderTarget(Optional.empty());
                    dragon.setOrderedToSit(true);
                    player.displayClientMessage(Component.translatable("dmr.command_mode.sit.text"), true);
                }
                case FOLLOW -> {
                    dragon.setOrderedToSit(false);
                    dragon.setWanderTarget(Optional.empty());
                    player.displayClientMessage(Component.translatable("dmr.command_mode.follow.text"), true);
                }
                case WANDER -> {
                    dragon.setOrderedToSit(false);
                    dragon.setWanderTarget(Optional.of(GlobalPos.of(level.dimension(), player.blockPosition())));
                    player.displayClientMessage(Component.translatable("dmr.command_mode.wander.text"), true);
                }
                case PASSIVE -> {
                    dragon.setAgroState(PASSIVE);
                    player.displayClientMessage(Component.translatable("dmr.command_mode.passive.text"), true);
                }
                case NEUTRAL -> {
                    dragon.setAgroState(NEUTRAL);
                    player.displayClientMessage(Component.translatable("dmr.command_mode.neutral.text"), true);
                }
                case AGGRESSIVE -> {
                    dragon.setAgroState(AGGRESSIVE);
                    player.displayClientMessage(Component.translatable("dmr.command_mode.aggressive.text"), true);
                }
                default -> throw new IllegalArgumentException("Unexpected value: " + Command.values()[command]);
            }
        }
    }
}
