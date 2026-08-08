package dmr.DragonMounts.registry;

import static dmr.DragonMounts.DMR.MOD_ID;

import dmr.DragonMounts.common.capability.DragonOwnerCapability;
import dmr.DragonMounts.network.packets.CompleteDataSync;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@EventBusSubscriber(modid = MOD_ID, bus = Bus.GAME)
public class ModCapabilities {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MOD_ID);

    public static Supplier<AttachmentType<DragonOwnerCapability>> PLAYER_CAPABILITY =
            ATTACHMENT_TYPES.register("dragon_owner", () -> AttachmentType.serializable(DragonOwnerCapability::new)
                    .copyOnDeath()
                    .build());

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent loggedInEvent) {
        Player player = loggedInEvent.getEntity();
        player.getData(PLAYER_CAPABILITY).setPlayerInstance(player);
        syncCapability(player);
    }

    /**
     * Wave 4: send the capability payload only to its owner via {@code sendToPlayer}
     * (not the tracking broadcast). Nothing client-side consumes another player's
     * capability, so broadcasting it wasted a 16-slot NBT payload on every tracking
     * client and leaked that data to bystanders. Matches the other CompleteDataSync
     * senders (DMRCommand, DragonWhistleItem, DragonWhistleEvent, DragonWhistleHandler,
     * PlayerJoinWorld).
     */
    public static void syncCapability(Player player) {
        // player.reviveCaps();
        PacketDistributor.sendToPlayer((ServerPlayer) player, new CompleteDataSync(player));
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent playerRespawnEvent) {
        Player player = playerRespawnEvent.getEntity();
        player.getData(PLAYER_CAPABILITY).setPlayerInstance(player);
        syncCapability(player);
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        Player player = event.getEntity();
        player.getData(PLAYER_CAPABILITY).setPlayerInstance(player);
        syncCapability(player);
    }
}
