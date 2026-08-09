package dmr.DragonMounts.common.events;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler.DragonInstance;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.network.packets.CompleteDataSync;
import dmr.DragonMounts.network.packets.DragonNBTSync;
import dmr.DragonMounts.network.packets.DragonRespawnDelayPacket;
import dmr.DragonMounts.registry.ModCapabilities;
import dmr.DragonMounts.registry.ModItems;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.worlddata.DragonWorldDataManager;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = DMR.MOD_ID)
public class DragonWhistleEvent {

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Wave 2: re-checks summons that were deferred because the bound dragon's chunk
        // was not loaded (chunk-ticket path in DragonWhistleHandler.callDragon).
        DragonWhistleHandler.processDeferredSummons(event.getServer());
        // Wave 5, Fix B4: discards any snapshot clones proven-and-queued by
        // onEntityJoinWorld's dedup check below. Never discarded synchronously from
        // inside EntityJoinLevelEvent itself (CME/ghost-entity risk).
        DragonWhistleHandler.processPendingReclaims(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Wave 5 review fix #5: DEFERRED_SUMMONS/PENDING_RECLAIMS hold absolute
        // tick-count deadlines that don't survive a server restart's tick counter
        // reset — clear both so a leftover entry from a previous session can never
        // fire a phantom summon or reclaim on the next one.
        DragonWhistleHandler.clearTransientState();
    }

    @SubscribeEvent
    public static void onWorldTick(LevelTickEvent.Post event) {
        if (!event.getLevel().isClientSide) {
            var data = DragonWorldDataManager.getInstance(event.getLevel());

            // Wave 3: the save()/load() fix un-masks this — deadDragons previously never
            // survived a restart, so deathDelay.get(uuid) never actually missed an entry
            // in practice. Now that dead-dragon state round-trips, deadDragons and
            // deathDelay/deathMessages are still written together at every mutation site
            // (setDragonDead/clearDragonData), but getOrDefault stays defensive against
            // any stale/legacy save where the two drifted apart — a raw .get(uuid) here
            // unboxes a null Integer and NPEs the level tick.
            for (var uuid : data.deadDragons) {
                var delay = data.deathDelay.getOrDefault(uuid, 0);
                if (delay > 0) {
                    data.deathDelay.put(uuid, delay - 1);
                    data.setDirty();
                }
            }

            for (var uuid : new CopyOnWriteArrayList<>(data.deadDragons)) {
                if (data.deathDelay.getOrDefault(uuid, 0) <= 0) {
                    DragonWorldDataManager.clearDragonData(event.getLevel(), uuid);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide) {
            if (event.getEntity() instanceof TameableDragonEntity dragon) {
                if (dragon.getOwner() != null && dragon.getOwner() instanceof Player player) {
                    var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);

                    // The dedup check is keyed on the dragon's whistle binding (derived from
                    // dragonUUID). An empty result means the dragon is NOT bound to any
                    // whistle — unbound dragons must never be dedup-checked (the old
                    // .orElse(0) fallback collapsed them onto slot 0 and deleted them on
                    // chunk load; upstream #64/#124).
                    var summonIndex = DragonWhistleHandler.getDragonSummonIndex(player, dragon.getDragonUUID());

                    if (summonIndex.isPresent() && cap.lastSummons != null) {
                        var index = summonIndex.getAsInt();
                        var expectedEntityId = cap.lastSummons.get(index);

                        if (expectedEntityId != null && !expectedEntityId.equals(dragon.getUUID())) {
                            var resolution = ServerConfig.DUPLICATE_RESOLUTION;
                            var boundInstance = cap.dragonInstances.get(index);

                            if (resolution != ServerConfig.DuplicateResolution.OFF) {
                                DMR.LOGGER.warn(
                                        "Duplicate dragon detected (resolution={}): dragonUUID={}, owner={} ({}),"
                                                + " expected entity {} (last known dimension {}), joining entity {}"
                                                + " in {} at ({}, {}, {})",
                                        resolution,
                                        dragon.getDragonUUID(),
                                        player.getName().getString(),
                                        player.getUUID(),
                                        expectedEntityId,
                                        boundInstance != null ? boundInstance.getDimension() : "unknown",
                                        dragon.getUUID(),
                                        event.getLevel().dimension().location(),
                                        dragon.getX(),
                                        dragon.getY(),
                                        dragon.getZ());
                            }

                            if (resolution == ServerConfig.DuplicateResolution.AGGRESSIVE) {
                                // duplicate_resolution semantics are NOT changed by reclaim
                                // (Wave 5 spec): AGGRESSIVE already cancels the JOINING
                                // entity below, unconditionally. Running reclaim first could
                                // queue a re-point/discard for that same joining entity and
                                // then have this cancel refuse its join anyway — losing BOTH
                                // entities. Leave AGGRESSIVE's pre-existing behavior alone.
                                //
                                // Cancelling the join event is the only removal mechanism
                                // permitted here — never remove/discard an entity from inside
                                // EntityJoinLevelEvent (CME/ghost-entity risk, advisor B4).
                                event.setCanceled(true);
                                return;
                            }

                            // Wave 5 review fix #7: reclaim runs ONLY under
                            // duplicate_resolution=LOG, never OFF. OFF is documented as
                            // "never touch duplicates" (ConfigProcessor.DuplicateResolution
                            // javadoc) and must keep meaning exactly that; LOG is the
                            // default this reclaim mechanism was designed for. Independent
                            // of the logging above — only ever acts on a PROVEN snapshot
                            // clone (the respawnedFromSnapshot provenance flag, within its
                            // evidence window), never a guess based on binding staleness
                            // (the destructive AGGRESSIVE bug this mechanism was built to
                            // avoid repeating). Safe here: it only queues a candidate for
                            // verification on a LATER tick — see
                            // DragonWhistleHandler#processPendingReclaims — it never mutates
                            // the binding or discards anything synchronously.
                            if (resolution == ServerConfig.DuplicateResolution.LOG) {
                                DragonWhistleHandler.maybeReclaimSnapshotClone(dragon, player, index, expectedEntityId);
                            }
                        }
                    }
                }
            }

            // Check if player is online and has a dragon instance
            if (event.getEntity() instanceof Player player) {
                var state = player.getData(ModCapabilities.PLAYER_CAPABILITY);

                if (!state.dragonInstances.isEmpty()) {
                    for (Map.Entry<Integer, DragonInstance> ent : state.dragonInstances.entrySet()) {
                        var index = ent.getKey();
                        var id = ent.getValue().getUUID();

                        if (player instanceof ServerPlayer spPlayer) {
                            var nbtData = state.dragonNBTs.get(index);
                            // Send the player their dragon data
                            PacketDistributor.sendToPlayer(spPlayer, new DragonNBTSync(index, nbtData));
                        }

                        var dragonWasKilled = DragonWorldDataManager.isDragonDead(event.getLevel(), id);

                        if (dragonWasKilled) {
                            var dragonRespawnDelay = DragonWorldDataManager.getDeathDelay(event.getLevel(), id);
                            var message = DragonWorldDataManager.getDeathMessage(event.getLevel(), id);
                            var mes = Component.Serializer.fromJsonLenient(
                                    message, event.getLevel().registryAccess());

                            if (mes != null) {
                                player.displayClientMessage(mes, false);
                            }

                            if (ServerConfig.ALLOW_RESPAWN) {
                                state.respawnDelays.put(index, dragonRespawnDelay);
                            } else {
                                state.dragonNBTs.remove(index);
                                state.respawnDelays.remove(index);
                                state.dragonInstances.remove(index);

                                if (player instanceof ServerPlayer spPlayer) {
                                    PacketDistributor.sendToPlayer(
                                            spPlayer, new DragonNBTSync(index, new CompoundTag()));
                                }
                            }

                            DragonWorldDataManager.clearDragonData(event.getLevel(), id);
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLivingUpdate(EntityTickEvent.Post event) {
        if (!event.getEntity().level.isClientSide) {
            if (event.getEntity() instanceof Player player) {
                var state = player.getData(ModCapabilities.PLAYER_CAPABILITY);
                for (Map.Entry<Integer, DragonInstance> ent : state.dragonInstances.entrySet()) {
                    var index = ent.getKey();
                    var id = ent.getValue().getUUID();

                    if (state.respawnDelays.containsKey(index) && state.respawnDelays.get(index) > 0) {
                        state.respawnDelays.put(index, state.respawnDelays.get(index) - 1);
                        PacketDistributor.sendToPlayer(
                                (ServerPlayer) player,
                                new DragonRespawnDelayPacket(index, state.respawnDelays.get(index)));
                        if (state.respawnDelays.get(index) == 0) {
                            DragonWorldDataManager.clearDragonData(player.level, id);
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityDeath(LivingDeathEvent event) {
        if (!event.getEntity().level.isClientSide) {
            if (event.getEntity() instanceof TameableDragonEntity dragon) {
                var mes = ((MutableComponent) event.getSource().getLocalizedDeathMessage(event.getEntity()))
                        .withStyle(ChatFormatting.RED);

                // Player is online, do death handle
                if (dragon.getOwner() != null && dragon.getOwner() instanceof Player player) {
                    // Death message already gets sent by vanilla, so until I can figure out how to
                    // cancel
                    // that, just let vanilla send the message when player is online
                    player.displayClientMessage(mes, false);

                    var summonIndex = DragonWhistleHandler.getDragonSummonIndex(player, dragon.getDragonUUID());

                    // An unbound dragon has no whistle state to clean up — bail instead of
                    // clobbering slot 0 (the old .orElse(0) landmine).
                    if (summonIndex.isEmpty()) {
                        return;
                    }
                    var index = summonIndex.getAsInt();

                    if (!ServerConfig.ALLOW_RESPAWN) {
                        var state = player.getData(ModCapabilities.PLAYER_CAPABILITY);

                        state.dragonInstances.remove(index);
                        state.dragonNBTs.remove(index);
                        state.respawnDelays.remove(index);
                        PacketDistributor.sendToPlayer((ServerPlayer) player, new CompleteDataSync(player));
                    } else if (ServerConfig.RESPAWN_TIME > 0) {
                        var state = player.getData(ModCapabilities.PLAYER_CAPABILITY);
                        state.respawnDelays.put(index, ServerConfig.RESPAWN_TIME * 20);

                        var whistle = ModItems.DRAGON_WHISTLES.get(index).get();

                        if (!player.getCooldowns().isOnCooldown(whistle)) {
                            player.getCooldowns().addCooldown(whistle, ServerConfig.RESPAWN_TIME * 20);
                        }

                        if (player instanceof ServerPlayer serverPlayer) {
                            PacketDistributor.sendToPlayer(serverPlayer, new CompleteDataSync(player));
                        }
                    }
                } else {
                    // Player isnt online, save to world
                    DragonWorldDataManager.setDragonDead(
                            dragon,
                            Component.Serializer.toJson(
                                    mes, event.getEntity().level.registryAccess()));
                }
            }
        }
    }
}
