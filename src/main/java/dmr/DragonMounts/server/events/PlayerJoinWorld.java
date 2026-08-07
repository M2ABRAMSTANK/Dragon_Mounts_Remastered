package dmr.DragonMounts.server.events;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.common.capability.DragonOwnerCapability;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler.DragonInstance;
import dmr.DragonMounts.network.packets.CompleteDataSync;
import dmr.DragonMounts.registry.ModCapabilities;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = DMR.MOD_ID)
public class PlayerJoinWorld {

    @SubscribeEvent
    public static void onPlayerJoinWorld(PlayerEvent.PlayerLoggedInEvent event) {
        if (!event.getEntity().level.isClientSide()) {
            var player = event.getEntity();
            var state = player.getData(ModCapabilities.PLAYER_CAPABILITY);

            // Reconcile before the state is serialized and synced to the client.
            reconcileDragonInstances(player, state);

            var tag = state.serializeNBT(player.level.registryAccess());
            PacketDistributor.sendToPlayer(
                    (ServerPlayer) player,
                    new CompleteDataSync(event.getEntity().getId(), tag));
        }
    }

    /**
     * Refreshes each whistle binding's DragonInstance (dimension + lastPos) from the
     * live entity, if it is currently loaded anywhere on the server (Wave 2).
     *
     * <p>
     * A bound dragon that changes dimension while its owner is OFFLINE cannot update
     * the owner's data attachment (NeoForge attachments only exist on loaded Player
     * entities), so the stored dimension may be stale at login. Dragons that are not
     * loaded anywhere are left as-is: the summon path's chunk-ticket re-check — and,
     * as a last resort, the snapshot respawn — covers them.
     */
    private static void reconcileDragonInstances(Player player, DragonOwnerCapability state) {
        var server = player.getServer();
        if (server == null) {
            return;
        }

        for (var entry : state.dragonInstances.entrySet()) {
            var instance = entry.getValue();
            if (instance == null || instance.getEntityId() == null || instance.getUUID() == null) {
                continue;
            }

            for (var level : server.getAllLevels()) {
                if (level.getEntity(instance.getEntityId()) instanceof TameableDragonEntity dragon
                        && instance.getUUID().equals(dragon.getDragonUUID())) {
                    var actualDimension = level.dimension().location().toString();
                    if (!actualDimension.equals(instance.getDimension())) {
                        DMR.LOGGER.info(
                                "Reconciling whistle binding of player {}: dragon {} moved {} -> {} while the"
                                        + " owner was offline",
                                player.getName().getString(),
                                instance.getUUID(),
                                instance.getDimension(),
                                actualDimension);
                    }
                    state.setDragonInstance(entry.getKey(), new DragonInstance(dragon));
                    break;
                }
            }
        }
    }
}
