package dmr.DragonMounts.network.packets;

import static dmr.DragonMounts.server.entity.DragonAgroState.*;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.network.AbstractMessage;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.util.PlayerStateUtils;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

        /**
         * W8-SUMMON-3: id-keyed lookup, replacing the {@code values()[command]}
         * ordinal-indexing this packet's raw int {@code command} field used to be fed
         * into directly. {@code command} is unvalidated client input (default -1 on the
         * no-arg constructor; a modified client can send anything) — ordinal indexing
         * threw {@code ArrayIndexOutOfBoundsException} on the server's main packet
         * thread for any out-of-range value, and would silently resolve to the WRONG
         * command the moment this enum's declaration order ever stopped coinciding
         * with its {@code id}s. Returns {@code null} rather than throwing so the caller
         * can reject the packet gracefully.
         */
        public static Command resolveCommand(int id) {
            return Arrays.stream(values()).filter(c -> c.id == id).findFirst().orElse(null);
        }
    }

    @Override
    public void handle(IPayloadContext context, Player player) {}

    /**
     * W8-SUMMON-3: per-player throttle on the expensive {@link
     * DragonWhistleHandler#findDragon} fallback below. The cheap same-level {@code
     * level.getEntity(entityId)} fast path is unthrottled (O(1), same cost as before
     * this fix); only a MISS on that path — meaning findDragon's two 100-block entity
     * sweeps would otherwise run — is rate-limited. Unlike WHISTLE, this packet has no
     * cooldown between decode and use, so without this a modified client could turn
     * every SIT/FOLLOW/WANDER/agro press into a main-thread cost amplifier at packet
     * rate. Keyed by player UUID; entries are small (one long per player who has ever
     * missed the fast path) and simply age out — no logout hook needed for a map this
     * cheap.
     */
    private static final ConcurrentHashMap<UUID, Long> LAST_FIND_DRAGON_TICK = new ConcurrentHashMap<>();

    private static final int FIND_DRAGON_THROTTLE_TICKS = 10;

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

        // W8-SUMMON-3: id-keyed lookup instead of Command.values()[command] — the raw
        // int is unvalidated client input, and ordinal indexing threw AIOOBE on the
        // server's main packet thread for any out-of-range value.
        var cmd = Command.resolveCommand(command);
        if (cmd == null) {
            DMR.LOGGER.warn(
                    "Received out-of-range dragon command id {} from player {}",
                    command,
                    player.getName().getString());
            return;
        }

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

        // W8-SUMMON-3: SIT/FOLLOW/WANDER/PASSIVE/NEUTRAL/AGGRESSIVE used to resolve
        // their target dragon via a bare same-level real-UUID lookup, which silently
        // fails whenever the dragon's entity section isn't currently "visible" — true
        // for cross-dimension dragons, and (per refute-summon.json) the more common
        // same-dimension-but-not-yet-promoted case too. Fast path first (cheap, O(1),
        // unthrottled): if the stored entityId still resolves in the player's own
        // level, use it directly, exactly as before this fix. Only a MISS falls
        // through to DragonWhistleHandler#findDragon's hardened, cross-dimension-aware
        // lookup — and that fallback is throttled (see LAST_FIND_DRAGON_TICK's
        // javadoc) so a modified client spamming this packet cannot turn every press
        // into two 100-block entity sweeps per tick.
        TameableDragonEntity dragon = null;
        if (level.getEntity(instance.getEntityId()) instanceof TameableDragonEntity fastDragon) {
            dragon = fastDragon;
        } else {
            long now = level.getGameTime();
            var lastFindDragonTick = LAST_FIND_DRAGON_TICK.get(player.getUUID());
            if (lastFindDragonTick == null || now - lastFindDragonTick >= FIND_DRAGON_THROTTLE_TICKS) {
                LAST_FIND_DRAGON_TICK.put(player.getUUID(), now);
                dragon = DragonWhistleHandler.findDragon(player, index);
            }
        }

        if (dragon == null) {
            player.displayClientMessage(
                    Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
            return;
        }

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
                // W8-SUMMON-3: DragonAI#getWanderTarget strips the GlobalPos's
                // dimension and paths toward the raw BlockPos unconditionally — setting
                // a target in the PLAYER's dimension for a dragon findDragon resolved
                // in a DIFFERENT dimension would silently mis-path it once it arrives
                // there. SIT/FOLLOW/agro-state setters need no such guard (pure
                // entity-local flags); FOLLOW's ambient AI already safely no-ops
                // cross-dimension since TamableAnimal#getOwner() is level-scoped.
                if (!dragon.level().dimension().equals(level.dimension())) {
                    player.displayClientMessage(
                            Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
                    break;
                }
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
            default -> throw new IllegalArgumentException("Unexpected value: " + cmd);
        }
    }
}
