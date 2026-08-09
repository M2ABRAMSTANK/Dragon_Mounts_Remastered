package dmr.DragonMounts.common.handlers;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.ModConstants;
import dmr.DragonMounts.common.capability.DragonOwnerCapability;
import dmr.DragonMounts.common.capability.types.NBTInterface;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.network.packets.CompleteDataSync;
import dmr.DragonMounts.network.packets.DragonNBTSync;
import dmr.DragonMounts.network.packets.DragonStatePacket;
import dmr.DragonMounts.registry.ModCapabilities;
import dmr.DragonMounts.registry.ModEntities;
import dmr.DragonMounts.registry.ModItems;
import dmr.DragonMounts.registry.ModSounds;
import dmr.DragonMounts.server.entity.DragonConstants;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.items.DragonWhistleItem;
import dmr.DragonMounts.server.worlddata.DragonWorldDataManager;
import dmr.DragonMounts.util.PlayerStateUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

public class DragonWhistleHandler {

    @Getter
    @NoArgsConstructor
    public static class DragonInstance implements NBTInterface {

        String dimension;
        UUID entityId;
        UUID UUID;

        /**
         * Last known block position of the dragon in {@link #dimension} (Wave 2, additive
         * NBT). Enables the summon path to ticket the dragon's chunk when it is not
         * currently loaded — "not loaded" must never be treated as "does not exist"
         * (upstream #124/#125). Null for legacy entries that predate this field.
         */
        BlockPos lastPos;

        /** Legacy-migration constructor: no live entity, so no known position. */
        public DragonInstance(String dimension, UUID entityId, UUID dragonUUID) {
            this.dimension = dimension;
            this.entityId = entityId;
            this.UUID = dragonUUID;
        }

        public DragonInstance(Level level, UUID entityId, UUID dragonUUID) {
            this.dimension = level.dimension().location().toString();
            this.entityId = entityId;
            this.UUID = dragonUUID;
        }

        public DragonInstance(TameableDragonEntity dragon) {
            this.dimension = dragon.level.dimension().location().toString();
            this.entityId = dragon.getUUID();
            this.UUID = dragon.getDragonUUID();
            this.lastPos = dragon.blockPosition();
        }

        @Override
        public CompoundTag writeNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension);
            tag.putUUID("entityId", entityId);
            tag.putUUID("uuid", UUID);
            if (lastPos != null) {
                tag.putLong("lastPos", lastPos.asLong());
            }
            return tag;
        }

        @Override
        public void readNBT(CompoundTag base) {
            if (base.contains("dimension")) {
                dimension = base.getString("dimension");
            }
            if (base.contains("entityId")) {
                entityId = base.getUUID("entityId");
            }
            if (base.contains("uuid")) {
                UUID = base.getUUID("uuid");
            }
            if (base.contains("lastPos")) {
                lastPos = BlockPos.of(base.getLong("lastPos"));
            }
        }
    }

    public static DragonWhistleItem getDragonWhistleItem(Player player) {
        var state = PlayerStateUtils.getHandler(player);
        Function<DragonWhistleItem, Boolean> isValid = (DragonWhistleItem whistleItem) -> {
            if (whistleItem.getColor() == null) {
                return false;
            }
            return state.dragonNBTs.containsKey(whistleItem.getColor().getId());
        };

        // Main hand - first
        if (player.getInventory().getSelected().getItem() instanceof DragonWhistleItem whistleItem) {
            if (isValid.apply(whistleItem)) {
                return whistleItem;
            }
        }

        // Off hand - second
        if (player.getInventory().offhand.getFirst().getItem() instanceof DragonWhistleItem whistleItem) {
            if (isValid.apply(whistleItem)) {
                return whistleItem;
            }
        }

        // Hotbar - third
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof DragonWhistleItem whistleItem) {
                if (isValid.apply(whistleItem)) {
                    return whistleItem;
                }
            }
        }

        // Inventory - fourth
        for (int i = 9; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).getItem() instanceof DragonWhistleItem whistleItem) {
                if (isValid.apply(whistleItem)) {
                    return whistleItem;
                }
            }
        }

        return null;
    }

    public static int getDragonSummonIndex(Player player) {
        var whistleItem = getDragonWhistleItem(player);
        return whistleItem != null ? whistleItem.getColor().getId() : -1;
    }

    /**
     * Finds the whistle slot a dragon (by dragonUUID) is bound to.
     *
     * <p>
     * Returns an empty OptionalInt for UNBOUND dragons. The previous {@code .orElse(0)}
     * fallback collapsed every owned-but-unbound dragon onto whistle slot 0, which made
     * the EntityJoinLevelEvent dedup check delete them on chunk load (upstream #64/#124;
     * defect 2 in .fork-notes/code-investigation.md). Callers MUST bail on empty.
     */
    public static OptionalInt getDragonSummonIndex(Player player, UUID dragonUUID) {
        var handler = PlayerStateUtils.getHandler(player);

        return handler.dragonInstances.entrySet().stream()
                .filter(entry -> entry.getValue() != null
                        && entry.getValue().UUID != null
                        && entry.getValue().UUID.equals(dragonUUID))
                .mapToInt(Entry::getKey)
                .findFirst();
    }

    public static void setDragon(Player player, TameableDragonEntity dragon, int index) {
        player.getData(ModCapabilities.PLAYER_CAPABILITY).setPlayerInstance(player);
        player.getData(ModCapabilities.PLAYER_CAPABILITY).setDragonToWhistle(dragon, index);
    }

    public static boolean canCall(Player player, int index) {
        var handler = PlayerStateUtils.getHandler(player);

        if (index == -1) {
            player.displayClientMessage(
                    Component.translatable("dmr.dragon_call.no_whistle").withStyle(ChatFormatting.RED), true);
            return false;
        }

        // Clean up invalid whistle data
        if (!player.level.isClientSide) {
            if ((handler.dragonNBTs.containsKey(index) && handler.dragonNBTs.get(index) == null)
                    || (handler.dragonInstances.containsKey(index) && handler.dragonInstances.get(index) == null)
                    || (handler.dragonInstances.containsKey(index) != handler.dragonNBTs.containsKey(index))) {
                handler.dragonNBTs.remove(index);
                handler.dragonInstances.remove(index);
                handler.respawnDelays.remove(index);
                PacketDistributor.sendToPlayer((ServerPlayer) player, new CompleteDataSync(player));
                // Wave 5, Fix A3: this desync-cleanup return previously left the player
                // with no feedback at all.
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.nodragon").withStyle(ChatFormatting.RED), true);
                return false;
            }
        }

        if (!handler.dragonInstances.containsKey(index) || handler.dragonInstances.get(index) == null) {
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.nodragon").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        if (handler.respawnDelays.getOrDefault(index, 0) > 0) {
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable(
                                        "dmr.dragon_call.respawn", handler.respawnDelays.getOrDefault(index, 0) / 20)
                                .withStyle(ChatFormatting.RED),
                        true);
            }
            return false;
        }

        if (player.getVehicle() != null) {
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.riding").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        // TODO Implement a better handling of space checking for game tests
        if (ServerConfig.CALL_CHECK_SPACE && !GameTestHooks.isGametestEnabled()) {
            if (!player.level.noBlockCollision(
                    null, player.getBoundingBox().move(0, 1, 0).inflate(1, 1, 1))) {
                if (!player.level.isClientSide) {
                    player.displayClientMessage(
                            Component.translatable("dmr.dragon_call.nospace").withStyle(ChatFormatting.RED), true);
                }
                return false;
            }
        }

        if (handler.lastCall != null && ServerConfig.WHISTLE_COOLDOWN_CONFIG > 0) {
            if (handler.lastCall + ServerConfig.WHISTLE_COOLDOWN_CONFIG > System.currentTimeMillis()) {
                if (!player.level.isClientSide) {
                    player.displayClientMessage(
                            Component.translatable("dmr.dragon_call.on_cooldown")
                                    .withStyle(ChatFormatting.RED),
                            true);
                }
                return false;
            }
        }

        return true;
    }

    /**
     * Attempts to summon the player's whistle-bound dragon.
     *
     * @return whether the summon succeeded (Wave 5, Fix A1) — propagates {@link
     *         #callDragon}'s result so callers can surface a failure to the player
     *         instead of silently swallowing it (the sound plays regardless; only the
     *         actual summon outcome is reported here).
     */
    public static boolean summonDragon(Player player) {
        if (player == null) {
            return false;
        }

        boolean called = callDragon(player);
        if (called) {
            var handler = PlayerStateUtils.getHandler(player);
            handler.lastCall = System.currentTimeMillis();
            ModItems.DRAGON_WHISTLES.values().forEach(s -> {
                if (!player.getCooldowns().isOnCooldown(s.get())) {
                    player.getCooldowns()
                            .addCooldown(
                                    s.get(),
                                    (int) TimeUnit.SECONDS.convert(
                                                    ServerConfig.WHISTLE_COOLDOWN_CONFIG, TimeUnit.MILLISECONDS)
                                            * 20);
                }
            });
        }
        return called;
    }

    /**
     * How long (in ticks) a deferred summon keeps re-checking for the ticketed chunk's
     * dragon before treating it as truly absent. Chunk loading is asynchronous and can
     * take more than a couple of ticks — giving up after a single fixed-delay re-check
     * would fall back to the snapshot respawn while the real dragon is mid-load, which
     * is exactly the clone bug this wave removes (#125).
     *
     * <p>
     * Wave 5, Fix B3: 20 -> 60 ticks (1s -> 3s). A cold entity-chunk load (disk read +
     * the FullChunkStatus promotion pipeline, driven by the distance-manager/background
     * executor entirely asynchronously — see the Blocker 2 comment in {@link
     * #callDragon}) can easily exceed 1 second under load; a timed-out deferred summon
     * now fails SAFE (Fix
     * B2's honest gate refuses and messages {@code not_found} instead of cloning), so
     * a longer deadline costs nothing but a slightly later "not found" message on the
     * rare genuinely-absent case, in exchange for far fewer premature clones.
     */
    private static final int DEFERRED_SUMMON_TIMEOUT_TICKS = 60;

    /**
     * How often (in ticks) a deferred summon's {@code findDragon} re-check (and its
     * region-ticket refresh) runs while waiting for the deadline. Wave 5 review
     * Blocker 2: the previous implementation called {@code findDragon} (and a blocking
     * chunk-task drain) EVERY tick, which is unnecessary — chunk promotion is driven by
     * the distance-manager/background executor on its own schedule, not by how often we
     * poll it. Re-checking every 4 ticks is still responsive while cutting the poll
     * rate ~5x.
     *
     * <p>
     * Verify-round polish #4: deliberately LESS than the {@code TicketType.POST_TELEPORT}
     * region ticket's 5-tick lifespan (not equal to it) — refreshing at exactly the same
     * cadence the ticket expires at leaves zero margin against normal tick-timing jitter
     * (a GC pause, a slow tick elsewhere) letting the ticket lapse for one tick right
     * before the refresh that was supposed to renew it.
     */
    private static final int DEFERRED_SUMMON_RECHECK_INTERVAL_TICKS = 4;

    private record DeferredSummon(UUID playerId, int index, int deadlineTick, int nextCheckTick) {}

    /** Summons whose stored chunk was just ticketed and that re-check on a later tick. */
    private static final List<DeferredSummon> DEFERRED_SUMMONS = new CopyOnWriteArrayList<>();

    public static boolean callDragon(Player player) {
        if (player != null) {
            DragonOwnerCapability cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);

            var summonItemIndex = getDragonSummonIndex(player);

            if (!canCall(player, summonItemIndex)) return false;

            Random rand = new Random();
            player.level.playSound(
                    null,
                    player.blockPosition(),
                    ModSounds.DRAGON_WHISTLE_SOUND.get(),
                    player.getSoundSource(),
                    0.75f,
                    (float) (ModConstants.DragonConstants.WHISTLE_BASE_PITCH
                            + rand.nextGaussian() / ModConstants.DragonConstants.WHISTLE_PITCH_DIVISOR));

            if (player.level.isClientSide) {
                return true; // Only process the remaining logic on the server side
            }

            // NOTE (Wave 2, B1): the manual inventory hand-off block that used to live
            // here is gone. Dragon inventories now live in a single global store on the
            // overworld (DragonInventoryHandler.getOrCreateInventory), so nothing needs
            // to move between per-dimension stores on a summon.

            TameableDragonEntity dragon = findDragon(player, summonItemIndex);

            if (dragon != null) {
                return summonExistingDragon(player, cap, summonItemIndex, dragon);
            }

            // The dragon did not resolve. If the binding records a last known position,
            // its chunk may simply be unloaded — "not loaded" must never be treated as
            // "does not exist" (upstream #124/#125). Ticket the chunk and re-check on a
            // later tick before falling back to the snapshot respawn.
            DragonInstance instance = cap.dragonInstances.get(summonItemIndex);
            if (instance != null
                    && instance.getLastPos() != null
                    && instance.getDimension() != null
                    && player instanceof ServerPlayer serverPlayer) {
                var storedLevel = resolveStoredLevel(serverPlayer.server, instance);

                if (storedLevel != null) {
                    boolean alreadyPending = DEFERRED_SUMMONS.stream()
                            .anyMatch(pending -> pending.playerId().equals(serverPlayer.getUUID())
                                    && pending.index() == summonItemIndex);
                    if (alreadyPending) {
                        // Wave 5 review fix #10: a re-press during the deferral window is a
                        // no-op, not a fresh summon attempt — return false so summonDragon
                        // does NOT stamp lastCall/cooldown for it.
                        //
                        // Verify-round polish #3: silently returning false here contradicted
                        // Fix A's whole point (never leave the player with no feedback) —
                        // reuses on_cooldown's wording ("You can't call your dragon yet!"),
                        // which reads naturally for "something is already in progress, wait a
                        // moment" without adding a new lang key.
                        if (!player.level.isClientSide) {
                            player.displayClientMessage(
                                    Component.translatable("dmr.dragon_call.on_cooldown")
                                            .withStyle(ChatFormatting.RED),
                                    true);
                        }
                        return false;
                    }

                    var chunkPos = new ChunkPos(instance.getLastPos());
                    // Wave 5, Fix B1: radius 2 -> 4. Radius 2 only makes the center 5x5
                    // chunks entity-accessible, but the widened-radius search box below
                    // (and the honest gate's chunk sweep in respawnDragonFromSnapshot) both
                    // span up to 9x9 chunks around lastPos — a narrower ticket left the
                    // outer ring of that search box unloaded, which is exactly the gap the
                    // honest gate (B2) needed closed to avoid a false "not found".
                    //
                    // Wave 5 review Blocker 2: NO synchronous drain and NO blocking
                    // ServerLevel#getChunk call here. Entity-section promotion is driven by
                    // PersistentEntitySectionManager#tick, which runs from ServerLevel#tick
                    // on the NORMAL tick loop — it cannot make progress while we block the
                    // server thread waiting for it, so the old drain was provably useless
                    // for exactly the thing it was trying to wait for, while still costing
                    // real stall time (and re-aging every ticket server-wide via its
                    // extra purgeStaleTickets calls). Just place the ticket and do ONE
                    // immediate (already-resolved-case) check; if that misses, defer and
                    // let processDeferredSummons poll asynchronously.
                    storedLevel
                            .getChunkSource()
                            .addRegionTicket(
                                    TicketType.POST_TELEPORT,
                                    chunkPos,
                                    SUMMON_CHUNK_TICKET_RADIUS,
                                    serverPlayer.getId());

                    var immediateCheck = findDragon(player, summonItemIndex);
                    if (immediateCheck != null) {
                        return summonExistingDragon(player, cap, summonItemIndex, immediateCheck);
                    }

                    DEFERRED_SUMMONS.add(new DeferredSummon(
                            serverPlayer.getUUID(),
                            summonItemIndex,
                            serverPlayer.server.getTickCount() + DEFERRED_SUMMON_TIMEOUT_TICKS,
                            serverPlayer.server.getTickCount() + DEFERRED_SUMMON_RECHECK_INTERVAL_TICKS));
                    DMR.LOGGER.debug(
                            "Dragon {} not loaded; ticketed chunk {} in {} and deferred the summon for {}",
                            instance.getUUID(),
                            chunkPos,
                            instance.getDimension(),
                            player.getName().getString());
                    return true;
                }
            }

            // "Confirmed dead" (never a clone candidate) vs. "just can't find it" (might
            // be a clone race) is decided inside respawnDragonFromSnapshot — see
            // isConfirmedDead.
            return respawnDragonFromSnapshot(player, cap, summonItemIndex);
        }

        return false;
    }

    /**
     * Runs the deferred (chunk-ticketed) summon re-checks. Called once per server tick
     * from {@link dmr.DragonMounts.common.events.DragonWhistleEvent}. Each deferred
     * summon re-checks every tick until the ticketed chunk's dragon becomes resolvable
     * (then it is summoned for real) or the deadline passes (then it is treated as
     * absent and respawned from the snapshot — the last resort).
     */
    public static void processDeferredSummons(MinecraftServer server) {
        if (DEFERRED_SUMMONS.isEmpty()) {
            return;
        }

        int tick = server.getTickCount();

        for (DeferredSummon pending : DEFERRED_SUMMONS) {
            // Wave 5 review Blocker 2: only do any work for this entry every
            // DEFERRED_SUMMON_RECHECK_INTERVAL_TICKS ticks (or on its deadline tick) —
            // findDragon and the region-ticket refresh don't need to run every single
            // tick; chunk promotion happens on its own schedule regardless of how often
            // we ask.
            if (tick < pending.nextCheckTick() && tick < pending.deadlineTick()) {
                continue;
            }

            var player = server.getPlayerList().getPlayer(pending.playerId());
            if (player == null) {
                DEFERRED_SUMMONS.remove(pending);
                continue; // Owner logged off while the summon was pending; drop it.
            }

            var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);

            // Keep the region ticket alive (POST_TELEPORT's lifespan is 5 ticks;
            // DEFERRED_SUMMON_RECHECK_INTERVAL_TICKS=4 refreshes it with a 1-tick
            // margin to spare, not exactly at expiry).
            var instance = cap.dragonInstances.get(pending.index());
            if (instance != null && instance.getLastPos() != null && instance.getDimension() != null) {
                var storedLevel = resolveStoredLevel(server, instance);
                if (storedLevel != null) {
                    storedLevel
                            .getChunkSource()
                            .addRegionTicket(
                                    TicketType.POST_TELEPORT,
                                    new ChunkPos(instance.getLastPos()),
                                    SUMMON_CHUNK_TICKET_RADIUS,
                                    player.getId());
                }
            }

            var dragon = findDragon(player, pending.index());

            if (dragon != null) {
                DEFERRED_SUMMONS.remove(pending);
                summonExistingDragon(player, cap, pending.index(), dragon);
            } else if (tick >= pending.deadlineTick()) {
                DEFERRED_SUMMONS.remove(pending);
                respawnDragonFromSnapshot(player, cap, pending.index());
            } else {
                DEFERRED_SUMMONS.remove(pending);
                DEFERRED_SUMMONS.add(new DeferredSummon(
                        pending.playerId(),
                        pending.index(),
                        pending.deadlineTick(),
                        tick + DEFERRED_SUMMON_RECHECK_INTERVAL_TICKS));
            }
        }
    }

    /**
     * Summons a live dragon to the player: cross-dimension via a real
     * {@code changeDimension} teleport (the #123/#125 fix — the entity moves, it is
     * never cloned), same-dimension via walk-or-teleport as before.
     */
    private static boolean summonExistingDragon(
            Player player, DragonOwnerCapability cap, int summonItemIndex, TameableDragonEntity dragon) {
        dragon.setHealth(Math.max(ModConstants.DragonConstants.MIN_DRAGON_HEALTH, dragon.getHealth()));

        // Eject third-party riders BEFORE any teleport: changeDimension drags passengers
        // through to the destination (vanilla re-mounts them on arrival).
        dragon.ejectPassengers();
        dragon.setOrderedToSit(false);
        dragon.setWanderTarget(Optional.empty());

        if (!player.level.dimension().equals(dragon.level().dimension())) {
            // Cross-dimension: teleport the real entity. PLACE_PORTAL_TICKET keeps the
            // arrival chunk held so the dragon is not immediately unloaded again.
            var targetLevel = (ServerLevel) player.level;
            var transition = new DimensionTransition(
                    targetLevel,
                    player.position(),
                    Vec3.ZERO,
                    dragon.getYRot(),
                    dragon.getXRot(),
                    DimensionTransition.PLACE_PORTAL_TICKET);
            var moved = dragon.changeDimension(transition);

            if (!(moved instanceof TameableDragonEntity movedDragon)) {
                // A CommonHooks/DMR veto returned null. This must NOT fall through to the
                // snapshot clone — that is the duplication engine (#125). Abort loudly.
                DMR.LOGGER.warn(
                        "Cross-dimension summon of dragon {} for player {} was vetoed by a dimension-change"
                                + " event; summon aborted",
                        dragon.getDragonUUID(),
                        player.getName().getString());
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.teleport_blocked")
                                .withStyle(ChatFormatting.RED),
                        true);
                return false;
            }

            dragon = movedDragon;
            // Final positioning through teleportTo — tracker-correct, unlike raw
            // position mutation (#111's invisible-dragon fix path).
            dragon.teleportTo(
                    targetLevel,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    Set.of(),
                    dragon.getYRot(),
                    dragon.getXRot());

            cap.lastSummons.put(summonItemIndex, dragon.getUUID());

            DMR.LOGGER.debug(
                    "Teleported dragon: {} across dimensions to player: {}",
                    dragon.getDragonUUID(),
                    player.getName().getString());
        } else if (dragon.position().distanceTo(player.position())
                <= DragonConstants.BASE_FOLLOW_RANGE * ModConstants.DragonConstants.FOLLOW_RANGE_MULTIPLIER) {
            // Walk to player
            cap.lastSummons.put(summonItemIndex, dragon.getUUID());

            DMR.LOGGER.debug(
                    "Making dragon: {} follow player: {}",
                    dragon.getDragonUUID(),
                    player.getName().getString());
        } else {
            // Teleport to player (same dimension) — through teleportTo, not setPos (#111)
            cap.lastSummons.put(summonItemIndex, dragon.getUUID());

            DMR.LOGGER.debug(
                    "Teleporting dragon: {} to player: {}",
                    dragon.getDragonUUID(),
                    player.getName().getString());

            dragon.teleportTo(
                    (ServerLevel) player.level,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    Set.of(),
                    dragon.getYRot(),
                    dragon.getXRot());
        }

        PacketDistributor.sendToPlayersTrackingEntity(
                dragon, new DragonStatePacket(dragon.getId(), ModConstants.DragonConstants.DRAGON_STATE_FOLLOW));
        return true;
    }

    /**
     * Wave 5 verify round (Blocker fix): is this whistle slot's dragon CONFIRMED dead —
     * i.e. did vanilla death already remove the original entity — rather than merely
     * "not found, might be a chunk-load race"? Two independent stores can record a
     * death, and a mint must check BOTH or it under-detects:
     *
     * <ul>
     * <li>{@code cap.respawnDelays.containsKey(index)} — set by
     * {@code DragonWhistleEvent#onEntityDeath}'s ONLINE-owner branch. Traced gap: with
     * {@code allow_respawn=true} and {@code respawn_time=0}, that branch used to skip
     * writing this entry entirely (neither the {@code !allow_respawn} arm nor the old
     * {@code respawn_time > 0} arm matched) — fixed there to always record an entry
     * (value 0) when respawn is allowed, regardless of the configured delay.</li>
     * <li>{@code DragonWorldDataManager.isDragonDead(level, dragonUUID)}, swept across
     * every loaded level — set by {@code onEntityDeath}'s OFFLINE-owner branch, which
     * also fires whenever the owner IS online but {@code TamableAnimal#getOwner()}'s
     * level-scoped lookup can't see them (a cross-dimension death). That record is only
     * ever copied into {@code respawnDelays} when the owner's {@code
     * EntityJoinLevelEvent} next fires for the level the dragon died in — a summon
     * attempted before that join (or in a session where it never happens) would see
     * neither the entity nor a {@code respawnDelays} entry, so checking that alone
     * under-detects. Checking the world-data record directly closes that gap.</li>
     * </ul>
     *
     * <p>
     * Callers that mint unflagged on a {@code true} result MUST consume whichever
     * store(s) matched (see {@link #clearWorldDeathRecord}) so a later, genuine
     * "can't find it, might be a race" mint for the same slot isn't wrongly treated as
     * confirmed-dead too.
     */
    private static boolean isConfirmedDead(
            MinecraftServer server, DragonOwnerCapability cap, int summonItemIndex, UUID dragonUUID) {
        if (cap.respawnDelays.containsKey(summonItemIndex)) {
            return true;
        }

        if (dragonUUID == null) {
            return false;
        }

        for (var level : server.getAllLevels()) {
            if (DragonWorldDataManager.isDragonDead(level, dragonUUID)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Consumes the world-data half of {@link #isConfirmedDead}'s death signal: clears
     * {@code dragonUUID}'s dead-dragon record from every level that has one. Companion
     * to the caller's own {@code cap.respawnDelays.remove(index)} for the
     * capability-side half — together they make the "confirmed dead" signal one-shot
     * regardless of which store it came from.
     */
    private static void clearWorldDeathRecord(MinecraftServer server, UUID dragonUUID) {
        if (dragonUUID == null) {
            return;
        }

        for (var level : server.getAllLevels()) {
            if (DragonWorldDataManager.isDragonDead(level, dragonUUID)) {
                DragonWorldDataManager.clearDragonData(level, dragonUUID);
            }
        }
    }

    /**
     * LAST RESORT (Wave 2): respawn the dragon from its NBT snapshot. This is the single
     * choke point every "the dragon didn't resolve" path funnels into — the deferred
     * (chunk-ticketed) summon's timeout in {@link #processDeferredSummons} and the
     * legacy-binding fallback in {@link #callDragon} both land here — so the confirmation
     * gate below is enforced exactly once, for everyone.
     *
     * <p>
     * Hard gates before ever minting a clone (fix-plan Wave 2 item 2 / BRIEF Bug 2
     * fix-path 1: "do not treat 'not currently loaded' as 'does not exist'"):
     * {@code allow_respawn} must be enabled; its stored dimension must resolve (Fix
     * B5); EVERY chunk in the search area the summon path actually scans — not just
     * the one chunk lastPos sits in — must have positively reached entity-loaded
     * status (Fix B2's honest gate, via {@link #decideSnapshotRespawn}) so that an
     * empty entity read there actually means something; and an all-levels existence
     * re-check must come back empty.
     *
     * <p>
     * A timed-out deferred summon and a chunk-ticket-less legacy binding both used to
     * fall through this method's predecessor straight into a clone: even the widened
     * 60-tick deadline (Wave 5, Fix B3) is not proof a cold-region read or a saturated
     * chunk pipeline actually finished, and a legacy binding with no recorded
     * {@code lastPos} can't even be ticketed to check.
     * Both are now refused (not_found, recoverable — the player can just call again) —
     * because {@code duplicate_resolution} defaults to LOG (never removes) as of this
     * wave, a wrongly-minted clone here is permanent, not recoverable.
     *
     * <p>
     * Wave 5 verify round: whether this mint follows a CONFIRMED death (see
     * {@link #isConfirmedDead}) is now decided in here, from {@code cap} and
     * {@code instance} — not threaded in from the caller — precisely because it needs
     * to check TWO independent stores (the capability's {@code respawnDelays} and the
     * per-level world-data dead-dragon record) and computing that at two separate call
     * sites risked exactly the kind of drift that under-detected real deaths in the
     * first place.
     */
    private static boolean respawnDragonFromSnapshot(Player player, DragonOwnerCapability cap, int summonItemIndex) {
        // The binding can be cleaned up between a deferred summon's scheduling and its
        // re-check (canCall's invalid-data sweep, dragon death without respawn, ...);
        // createDragonEntity dereferences the instance, so bail here instead.
        var instance = cap.getDragonInstance(summonItemIndex);
        if (instance == null || cap.dragonNBTs.get(summonItemIndex) == null) {
            // Wave 5, Fix A3: this bare return used to leave the player with no feedback
            // at all (the earlier unconditional "whistle" action-bar in
            // DragonCommandPacket masked it before Fix A2).
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        if (!ServerConfig.ALLOW_RESPAWN) {
            DMR.LOGGER.warn(
                    "Refusing to respawn dragon {} for player {} from snapshot: allow_respawn is disabled",
                    instance.getUUID(),
                    player.getName().getString());
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        var server = player.level.getServer();
        if (server == null || instance.getUUID() == null) {
            // No server/dragonUUID reference to confirm absence with at all — refuse to
            // clone blind rather than assume the dragon is gone.
            DMR.LOGGER.warn(
                    "Refusing to respawn dragon for player {} from snapshot: no server/dragonUUID reference"
                            + " available to confirm absence",
                    player.getName().getString());
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        // Gate 1 (the fix for the deferred-timeout and legacy-binding holes): a "no
        // entity found" read only means something if we can prove the place we looked
        // had actually finished loading. ServerLevel#getEntity and the AABB/type-scanned
        // queries below are both gated on FullChunkStatus reaching ENTITY_TICKING
        // (PersistentEntitySectionManager only promotes a chunk's entity sections out of
        // HIDDEN at that point) — a chunk that hasn't gotten there yet reads as "empty"
        // whether or not the dragon is actually sitting in it. A deferred-summon deadline
        // is a budget, not a promise that promotion finished; a legacy binding with no
        // recorded lastPos can't even be pointed at a chunk to check. Either way, without
        // positive proof, refuse rather than guess — the binding regains a usable lastPos
        // the next time its dragon is naturally sighted (TameableDragonEntity's periodic
        // baseTick refresh or its next changeDimension), at which point a retry can
        // actually confirm absence.
        if (instance.getDimension() == null || instance.getLastPos() == null) {
            DMR.LOGGER.warn(
                    "Refusing to respawn dragon {} for player {} from snapshot: no recorded dimension/position to"
                            + " verify a chunk actually finished loading (legacy pre-Wave-2 binding?) — cloning"
                            + " here could duplicate a dragon we simply have no way to check",
                    instance.getUUID(),
                    player.getName().getString());
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        var storedLevel = resolveStoredLevel(server, instance);
        if (storedLevel == null) {
            DMR.LOGGER.warn(
                    "Refusing to respawn dragon {} for player {} from snapshot: could not resolve its stored"
                            + " dimension ({}) — malformed/unknown, or the dimension isn't currently loaded",
                    instance.getUUID(),
                    player.getName().getString(),
                    instance.getDimension());
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        // Wave 5, Fix B2 (honest gate): the old check only probed the SINGLE center
        // chunk lastPos sits in — the one the summon path itself had just force-loaded
        // via the region ticket, so it proved the ticket worked, not that the dragon
        // was actually absent. findDragon's widened-radius rescue scan (and the
        // proximity scan below it) reads a box up to 9x9 chunks around lastPos, so
        // EVERY chunk that box touches must be entity-loaded before an empty read
        // anywhere in it is trustworthy.
        var chunksToCheck = searchBoxChunks(instance.getLastPos());
        var decision = decideSnapshotRespawn(
                storedLevel, chunksToCheck, (level, chunkPos) -> level.areEntitiesLoaded(chunkPos.toLong()), () -> {
                    // Gate 2: only reached once every chunk above is confirmed loaded, so an
                    // empty read here is trustworthy. Still sweep ALL levels (not just
                    // storedLevel) — belt-and-suspenders against the binding's recorded
                    // dimension itself being wrong (e.g. a third-party mod teleport findDragon
                    // never looked at).
                    for (var candidateLevel : server.getAllLevels()) {
                        var liveMatches = candidateLevel.getEntities(
                                ModEntities.DRAGON_ENTITY.get(), (TameableDragonEntity candidate) -> instance.getUUID()
                                        .equals(candidate.getDragonUUID()));
                        if (!liveMatches.isEmpty()) {
                            // A live dragon with this dragonUUID exists somewhere. Minting a
                            // clone here would duplicate it — abort instead (#125).
                            DMR.LOGGER.warn(
                                    "Aborting snapshot respawn of dragon {} for player {}: a live entity with this"
                                            + " dragonUUID was found in {} at {} — the binding's recorded"
                                            + " dimension ({}) was stale, not the dragon actually being absent",
                                    instance.getUUID(),
                                    player.getName().getString(),
                                    candidateLevel.dimension().location(),
                                    liveMatches.get(0).blockPosition(),
                                    instance.getDimension());
                            return true;
                        }
                    }
                    return false;
                });

        if (decision != SnapshotRespawnDecision.ALLOW) {
            if (decision == SnapshotRespawnDecision.CHUNK_NOT_LOADED) {
                DMR.LOGGER.warn(
                        "Refusing to respawn dragon {} for player {} from snapshot: the search area around {} in"
                                + " {} has not fully reached entity-loaded status — an empty read there proves"
                                + " nothing, only that loading isn't finished; try the summon again",
                        instance.getUUID(),
                        player.getName().getString(),
                        instance.getLastPos(),
                        instance.getDimension());
            }
            // DRAGON_STILL_PRESENT is already logged by the supplier above.
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        TameableDragonEntity newDragon = cap.createDragonEntity(player, player.level, summonItemIndex);

        if (newDragon == null) {
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        boolean confirmedDead = isConfirmedDead(server, cap, summonItemIndex, instance.getUUID());

        DMR.LOGGER.warn(
                "Respawning dragon: {} from snapshot for player: {} — {}",
                newDragon.getDragonUUID(),
                player.getName().getString(),
                confirmedDead
                        ? "confirmed dead (respawn-delay entry or world-data death record present); minting"
                                + " unflagged"
                        : "no live entity found in its stored dimension");

        if (confirmedDead) {
            // Wave 5 review Blocker 1(a) / verify round: vanilla already removed the
            // original on death — this can never be a clone. Do NOT flag it, and
            // consume whichever death record(s) proved that (isConfirmedDead's javadoc)
            // so a LATER, genuine "can't find it" mint for this same slot isn't wrongly
            // treated as confirmed-dead too.
            cap.respawnDelays.remove(summonItemIndex);
            clearWorldDeathRecord(server, instance.getUUID());
        } else {
            // Wave 5, Fix B4: flag this entity as a snapshot-respawn clone BEFORE it
            // joins the level, so the join-time dedup check can prove (never guess)
            // which entity to reclaim if this mint ever turns out to have raced a
            // still-live original. Wave 5 review Blocker 1(b): also stamp the game time
            // of the mint — maybeReclaimSnapshotClone only trusts this flag as
            // clone-proof within a bounded evidence window (see
            // SNAPSHOT_CLONE_EVIDENCE_WINDOW_TICKS); a clone that survived
            // contest-free well past that window has accrued its own progression and
            // must not be silently discarded.
            newDragon.setRespawnedFromSnapshot(true);
            newDragon.setSnapshotMintGameTime(server.overworld().getGameTime());
        }

        newDragon.setPos(player.getX(), player.getY(), player.getZ());
        player.level.addFreshEntity(newDragon);

        // Wave 2 fix: createDragonEntity's setDragonToWhistle call wrote the DragonInstance
        // (including lastPos) from the snapshot's pre-move position, BEFORE the setPos
        // above relocated the entity next to the player — refresh it now so the binding
        // points at where the dragon actually is (code-investigation.md defect: "lastPos
        // is stale by construction").
        cap.setDragonInstance(summonItemIndex, new DragonInstance(newDragon));

        PacketDistributor.sendToPlayersTrackingEntity(
                newDragon, new DragonStatePacket(newDragon.getId(), ModConstants.DragonConstants.DRAGON_STATE_FOLLOW));

        return true;
    }

    public static TameableDragonEntity findDragon(Player player, int index) {
        if (player.level.isClientSide) {
            return null;
        }

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        var instance = cap.dragonInstances.get(index);

        // NPE guard (Wave 2): the old implementation fell through to a final log line
        // that dereferenced instance unconditionally.
        if (instance == null) {
            DMR.LOGGER.debug(
                    "No dragon bound to whistle slot {} for player: {}",
                    index,
                    player.getName().getString());
            return null;
        }

        var server = player.level.getServer();
        assert server != null;

        // Stored-dimension branch (Wave 2): resolve the dragon in whatever dimension the
        // binding says it is in — not just the player's own. The dragonUUID verify
        // guards against the entity id resolving to a different (recycled/legacy) entity.
        if (instance.getDimension() != null) {
            // Wave 5, Fix B5: resolveStoredLevel try/catches the malformed-dimension case
            // (legacy ResourceKey#toString() data, "ResourceKey[minecraft:dimension /
            // minecraft:overworld]") that ResourceLocation.parse used to throw on here —
            // Wave 1 only fixed the WRITER side of this, not this (or three other) reader.
            var storedLevel = resolveStoredLevel(server, instance);

            if (storedLevel != null) {
                if (instance.getEntityId() != null) {
                    var entity = storedLevel.getEntity(instance.getEntityId());
                    if (entity instanceof TameableDragonEntity dragon
                            && dragon.getDragonUUID() != null
                            && dragon.getDragonUUID().equals(instance.getUUID())) {
                        DMR.LOGGER.debug(
                                "Found dragon: {} from entity id: {} in stored dimension: {}",
                                dragon,
                                instance.getEntityId(),
                                instance.getDimension());
                        return dragon;
                    }
                }

                // Widened-radius fallback (Wave 2 fix): storedLevel.getEntity(UUID) above
                // requires BOTH the exact stored entityId AND that entity's chunk section
                // to already be promoted "visible" (PersistentEntitySectionManager) — a
                // status change driven by the chunk-ticket/distance-manager pipeline that
                // callDragon/processDeferredSummons place a region ticket for and then
                // poll (never block) waiting on, before calling in here. Area-bounded
                // entity queries
                // are gated by that SAME visibility threshold (confirmed against
                // decompiled 1.21.1 sources: EntitySectionStorage#forEachAccessibleNonEmptySection
                // filters on Visibility#isAccessible(), same as EntityLookup's UUID/id
                // maps) — so this is not a bypass, it is a wider net over the SAME
                // now-visible chunk range: it tolerates lastPos drift (the dragon walked a
                // short distance since its instance was last refreshed) and a
                // recycled/mismatched entityId, without requiring the exact stored id to
                // still be correct. Anchored on lastPos, which is refreshed on every
                // dimension change and periodically while tamed
                // (TameableDragonEntity#baseTick).
                if (instance.getLastPos() != null) {
                    var searchBox = AABB.ofSize(
                            Vec3.atCenterOf(instance.getLastPos()),
                            ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS,
                            ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS,
                            ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS);
                    var found = storedLevel.getEntitiesOfClass(
                            TameableDragonEntity.class,
                            searchBox,
                            candidate -> candidate.getDragonUUID() != null
                                    && candidate.getDragonUUID().equals(instance.getUUID()));

                    if (found.size() > 1) {
                        DMR.LOGGER.warn(
                                "Widened-radius scan found {} dragons sharing dragonUUID {} in stored dimension"
                                        + " {} near {} — picking the closest; duplicates are likely legacy clones"
                                        + " (see /dmr duplicates)",
                                found.size(),
                                instance.getUUID(),
                                instance.getDimension(),
                                instance.getLastPos());
                    }

                    if (!found.isEmpty()) {
                        var closest = found.stream()
                                .min(java.util.Comparator.comparingDouble(
                                        candidate -> candidate.blockPosition().distSqr(instance.getLastPos())))
                                .orElseThrow();
                        DMR.LOGGER.debug(
                                "Found dragon: {} via widened-radius scan near lastPos {} in stored dimension: {}",
                                closest,
                                instance.getLastPos(),
                                instance.getDimension());
                        return closest;
                    }
                }
            }
        }

        DMR.LOGGER.debug(
                "Searching for dragon: {} near player: {}",
                instance.getUUID(),
                player.getName().getString());

        var entities = player.level.getNearbyEntities(
                TameableDragonEntity.class,
                TargetingConditions.forNonCombat(),
                player,
                AABB.ofSize(
                        player.position(),
                        ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS,
                        ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS,
                        ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS));

        TameableDragonEntity scanMatch = null;
        int scanMatches = 0;
        for (var entity : entities) {
            if (entity.getDragonUUID() != null && entity.getDragonUUID().equals(instance.getUUID())) {
                scanMatches++;
                if (scanMatch == null) {
                    scanMatch = entity; // getNearbyEntities sorts by distance; keep the closest
                }
            }
        }

        if (scanMatches > 1) {
            DMR.LOGGER.warn(
                    "Proximity scan found {} dragons sharing dragonUUID {} near player {} — picking the closest;"
                            + " duplicates are likely legacy clones (see /dmr duplicates)",
                    scanMatches,
                    instance.getUUID(),
                    player.getName().getString());
        }

        if (scanMatch != null) {
            // The stored entityId/dimension lookup missed but the dragon is right here:
            // the binding is stale. Log loudly — this is the rescue path, not the norm.
            DMR.LOGGER.warn(
                    "Whistle binding for dragon {} of player {} was stale (stored dimension {}, entity id {});"
                            + " rescued via the proximity scan",
                    instance.getUUID(),
                    player.getName().getString(),
                    instance.getDimension(),
                    instance.getEntityId());
            return scanMatch;
        }

        DMR.LOGGER.debug(
                "Could not find dragon: {} for player: {}",
                instance.getUUID(),
                player.getName().getString());
        return null;
    }

    /**
     * Resolves a {@link DragonInstance}'s recorded dimension string to a live {@link
     * ServerLevel}, or {@code null} if the dimension is unrecorded, malformed, or
     * unknown to this server (Wave 5, Fix B5).
     *
     * <p>
     * {@code ResourceLocation.parse} throws on malformed input — most notably legacy
     * data written via {@code ResourceKey#toString()} instead of
     * {@code ResourceKey#location()#toString()} (yields strings like
     * {@code "ResourceKey[minecraft:dimension / minecraft:overworld]"}, not a valid
     * resource location). Wave 1 fixed the one WRITER site that produced this
     * (DragonOwnerCapability's legacy migration); this fixes the remaining FOUR reader
     * sites that all repeated the same unguarded parse. Every failure here is treated
     * as "dimension unknown" — never as a reason to throw, and never as grounds to
     * mint a snapshot clone (the caller's absence-confirmation gates still apply on
     * top of this).
     */
    /**
     * Dimension strings this method has already warned about (Wave 5 review fix #12):
     * a hot binding with a malformed/unknown dimension gets re-resolved on every
     * summon-related check, so logging unconditionally would spam the log at the same
     * rate the old unguarded parse used to CRASH at. One WARN per unique string for
     * the life of the server is enough for an operator to notice and fix the data.
     */
    private static final Set<String> WARNED_MALFORMED_DIMENSIONS = ConcurrentHashMap.newKeySet();

    public static @Nullable ServerLevel resolveStoredLevel(MinecraftServer server, DragonInstance instance) {
        if (instance == null || instance.getDimension() == null) {
            return null;
        }

        try {
            var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(instance.getDimension()));
            return server.getLevel(key);
        } catch (Exception e) {
            // Wave 5 review fix #12: no stack trace (this is routine legacy/malformed
            // data, not an exceptional failure — e.toString() names the problem without
            // a multi-line trace), and only once per unique malformed string.
            if (WARNED_MALFORMED_DIMENSIONS.add(instance.getDimension())) {
                DMR.LOGGER.warn(
                        "Could not resolve dimension '{}' for a dragon whistle binding — malformed or unknown"
                                + " dimension key ({}); treating the dragon's location as unknown rather than"
                                + " throwing or assuming it is absent",
                        instance.getDimension(),
                        e.toString());
            }
            return null;
        }
    }

    /** Outcome of {@link #decideSnapshotRespawn}. */
    public enum SnapshotRespawnDecision {
        /** Every chunk was loaded and no live dragon was found — safe to mint. */
        ALLOW,
        /** At least one chunk in the search area has not reached entity-loaded status. */
        CHUNK_NOT_LOADED,
        /** Every chunk was loaded, and a live dragon with this UUID was found. */
        DRAGON_STILL_PRESENT
    }

    /**
     * Pure decision table for "may we mint a snapshot clone?" (Wave 5, Fix B2),
     * extracted out of {@link #respawnDragonFromSnapshot} so the honest-gate logic is
     * unit-testable without real chunk loads/unloads.
     *
     * <p>
     * Decision table: any chunk in {@code chunksToCheck} not entity-loaded ->
     * {@link SnapshotRespawnDecision#CHUNK_NOT_LOADED} (an empty read anywhere in an
     * only-partly-loaded search area proves nothing); every chunk loaded and the
     * dragon found live somewhere -> {@link SnapshotRespawnDecision#DRAGON_STILL_PRESENT};
     * every chunk loaded and the dragon NOT found anywhere ->
     * {@link SnapshotRespawnDecision#ALLOW}. {@code dragonFoundLive} is a
     * {@link BooleanSupplier} (not a plain boolean) so production callers can skip the
     * expensive all-levels sweep entirely when the chunk gate already fails.
     *
     * @param storedLevel        the level {@code chunksToCheck} lies in; passed through
     *                           to {@code entitiesLoadedProbe} unmodified (never
     *                           dereferenced by this method itself, so it may be
     *                           {@code null} in a test double)
     * @param chunksToCheck      every chunk the caller's subsequent existence read
     *                           actually scans
     * @param entitiesLoadedProbe per-chunk entity-loaded probe (production:
     *                           {@code ServerLevel#areEntitiesLoaded}; tests: a canned
     *                           lookup)
     * @param dragonFoundLive    lazily evaluated ONLY when every chunk passes the gate
     */
    public static SnapshotRespawnDecision decideSnapshotRespawn(
            ServerLevel storedLevel,
            List<ChunkPos> chunksToCheck,
            BiPredicate<ServerLevel, ChunkPos> entitiesLoadedProbe,
            BooleanSupplier dragonFoundLive) {
        for (ChunkPos chunkPos : chunksToCheck) {
            if (!entitiesLoadedProbe.test(storedLevel, chunkPos)) {
                return SnapshotRespawnDecision.CHUNK_NOT_LOADED;
            }
        }

        return dragonFoundLive.getAsBoolean()
                ? SnapshotRespawnDecision.DRAGON_STILL_PRESENT
                : SnapshotRespawnDecision.ALLOW;
    }

    /**
     * Wave 5 review HIGH-3: vanilla's {@code EntitySectionStorage} (backing both
     * {@code ServerLevel#getEntitiesOfClass} and {@code EntityLookup}'s AABB queries)
     * inflates the query AABB by this many blocks on every axis BEFORE computing which
     * chunk sections to sweep — confirmed against decompiled 1.21.1 sources
     * ({@code EntitySectionStorage#forEachAccessibleNonEmptySection} sections
     * {@code aabb.inflate(2.0)}, not the raw query box). {@link #searchBoxChunks} must
     * mirror that inflation exactly, or for any {@code lastPos} whose x/z falls in the
     * ~44% of positions where the raw (uninflated) chunk bounds differ from the
     * inflated ones (x or z mod 16 in {2,3,12,13}), the honest gate would certify a
     * chunk ring as "loaded" that {@link #findDragon}'s widened-radius scan can
     * actually read entities out of — reopening exactly the residual clone hole Fix B2
     * exists to close.
     */
    public static final double SEARCH_BOX_INFLATION_BLOCKS = 2.0;

    /**
     * Region-ticket radius (in chunks) held around a stored {@code lastPos} while
     * resolving a summon (Wave 5, Fix B1, widened 2 -> 4). Must stay large enough that
     * every chunk {@link #searchBoxChunks} can require entity-loaded is within
     * Chebyshev distance of {@code lastPos}'s own chunk, or the ticket won't actually
     * cover the honest gate's search area and a legitimate summon would spuriously
     * refuse. {@code DragonWhistleHandlerLogicTests#ticketRadiusCoversWorstCaseSearchBox}
     * asserts this coupling so a future {@code DRAGON_SEARCH_RADIUS} bump fails a test
     * instead of silently reopening the B1/B2 gap.
     */
    public static final int SUMMON_CHUNK_TICKET_RADIUS = 4;

    /**
     * Chunk coordinates intersecting the same (vanilla-inflated) search box
     * {@link #findDragon}'s widened-radius scan reads (Wave 5, Fix B2 / review
     * HIGH-3): centered on {@code center}, sized
     * {@link ModConstants.DragonConstants#DRAGON_SEARCH_RADIUS} blocks on both
     * horizontal axes, inflated by {@link #SEARCH_BOX_INFLATION_BLOCKS} on every side
     * to match vanilla's {@code EntitySectionStorage} query inflation exactly. Package-
     * visible (not private) so it can be unit-tested directly.
     */
    public static List<ChunkPos> searchBoxChunks(BlockPos center) {
        double reach = ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS / 2.0 + SEARCH_BOX_INFLATION_BLOCKS;
        var min = new ChunkPos(BlockPos.containing(center.getX() - reach, center.getY(), center.getZ() - reach));
        var max = new ChunkPos(BlockPos.containing(center.getX() + reach, center.getY(), center.getZ() + reach));

        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = min.x; x <= max.x; x++) {
            for (int z = min.z; z <= max.z; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }

    /**
     * How long (in ticks) a {@code respawnedFromSnapshot} flag is trusted as clone
     * proof, from its {@code snapshotMintGameTime} stamp (Wave 5 review Blocker 1(b)).
     * 168000 = 7 * 24000 — 7 IN-GAME days of {@code Level#getGameTime()}, which only
     * advances while the server is actually ticking. At the standard 20 ticks/second
     * that is only ~2h20m of real server UPTIME (168000 / 20 / 60 = 140 minutes) — NOT
     * 7 real-world calendar days. Read literally as "7 days" this sounds like a
     * generous window; an operator sizing it against actual playtime should use the
     * ~2h20m figure. A clone that has survived unchallenged for that long has accrued
     * its own progression (taming interactions, inventory, playtime) — silently
     * discarding it on a stale flag would BE the duplication bug this mechanism exists
     * to prevent, just delayed. Past the window (or with no recorded mint time — e.g. a
     * legacy entity, or one flagged before this fix), a mismatch falls through to plain
     * {@code duplicate_resolution=LOG} behavior: both entities survive, logged for
     * operator triage.
     */
    public static final long SNAPSHOT_CLONE_EVIDENCE_WINDOW_TICKS = 168_000L;

    /**
     * A same-dragonUUID mismatch where exactly one live entity carries
     * {@code respawnedFromSnapshot}, detected in {@code DragonWhistleEvent}'s join
     * handler and queued here for verification and action on the NEXT server tick
     * (Wave 5, Fix B4; review fix #9). Deliberately carries only UUIDs/dimensions, not
     * entity references — both entities are re-resolved via {@code Level#getEntity} at
     * process time so nothing is ever mutated or discarded on stale information.
     */
    private record PendingReclaim(
            UUID ownerId,
            int index,
            UUID dragonUUID,
            ResourceKey<Level> cloneDimension,
            UUID cloneEntityUUID,
            ResourceKey<Level> originalDimension,
            UUID originalEntityUUID) {}

    /**
     * Queued reclaims awaiting verification (Wave 5, Fix B4). Never acted on
     * synchronously from inside {@code EntityJoinLevelEvent}: removing/discarding an
     * entity — or mutating the owner's whistle binding — from within that event risks
     * a ConcurrentModificationException on the level's entity-iteration machinery and
     * ghost-entity bookkeeping (the same reason {@code duplicate_resolution=AGGRESSIVE}
     * only ever cancels the event rather than discarding directly).
     */
    private static final List<PendingReclaim> PENDING_RECLAIMS = new CopyOnWriteArrayList<>();

    /**
     * Runs queued snapshot-clone reclaims (Wave 5, Fix B4; review fix #9). Called once
     * per server tick from {@link dmr.DragonMounts.common.events.DragonWhistleEvent},
     * mirroring {@link #processDeferredSummons}'s pattern.
     *
     * <p>
     * This is where ALL of the actual verification and mutation happens — detection
     * (in {@link #maybeReclaimSnapshotClone}) only decides whether a reclaim is
     * PLAUSIBLE and queues it; this method re-resolves both entities by UUID, and only
     * if the original still resolves and is alive does it re-point the whistle
     * binding (index, NBT snapshot, lastSummons), push the owner a fresh sync, and
     * discard the clone — atomically, in that order, so a client is never told about a
     * binding for an entity that then fails to get discarded (or vice versa). If the
     * original vanished (its own join was cancelled by a later listener, it died,
     * etc.) the reclaim is dropped WITHOUT touching the clone — better to leave an
     * un-reclaimed clone for operator triage than to discard the only entity left.
     */
    public static void processPendingReclaims(MinecraftServer server) {
        if (PENDING_RECLAIMS.isEmpty()) {
            return;
        }

        for (PendingReclaim pending : PENDING_RECLAIMS) {
            PENDING_RECLAIMS.remove(pending);

            var owner = server.getPlayerList().getPlayer(pending.ownerId());
            if (owner == null) {
                continue; // Owner logged off since the reclaim was queued; drop it.
            }

            var originalLevel = server.getLevel(pending.originalDimension());
            TameableDragonEntity original = originalLevel != null
                            && originalLevel.getEntity(pending.originalEntityUUID()) instanceof TameableDragonEntity o
                    ? o
                    : null;

            if (original == null || !original.isAlive()) {
                DMR.LOGGER.info(
                        "Snapshot-clone reclaim dropped for dragonUUID {} (owner {}): the proven original {} no"
                                + " longer resolves — leaving the flagged entity untouched",
                        pending.dragonUUID(),
                        owner.getName().getString(),
                        pending.originalEntityUUID());
                continue;
            }

            var cloneLevel = server.getLevel(pending.cloneDimension());
            TameableDragonEntity clone = cloneLevel != null
                            && cloneLevel.getEntity(pending.cloneEntityUUID()) instanceof TameableDragonEntity c
                    ? c
                    : null;

            if (clone == null || !clone.isAlive() || !clone.isRespawnedFromSnapshot()) {
                continue; // Already gone, or unflagged since queued — nothing to reclaim.
            }

            // Wave 5 review Blocker 1(b): re-check the evidence window at ACT time, not
            // just at detection — the authoritative "is this still clone-proof" check
            // belongs where the decision to discard is actually made.
            long mintTime = clone.getSnapshotMintGameTime();
            long gameTime = server.overworld().getGameTime();
            if (mintTime < 0 || gameTime - mintTime > SNAPSHOT_CLONE_EVIDENCE_WINDOW_TICKS) {
                DMR.LOGGER.info(
                        "Snapshot-clone reclaim SKIPPED for dragonUUID {} (owner {}): flagged entity {} was minted"
                                + " too long ago (or has no recorded mint time) to trust as clone-proof — both"
                                + " entities left alone",
                        pending.dragonUUID(),
                        owner.getName().getString(),
                        clone.getUUID());
                continue;
            }

            if (clone.isVehicle()) {
                DMR.LOGGER.info(
                        "Snapshot-clone reclaim SKIPPED for dragonUUID {} (owner {}): flagged clone {} currently"
                                + " has a passenger — leaving both entities in place",
                        pending.dragonUUID(),
                        owner.getName().getString(),
                        clone.getUUID());
                continue;
            }

            // Wave 5 review fix #6: refresh the NBT snapshot from the CONFIRMED
            // original (not the clone's — the clone-era snapshot would resurrect
            // clone stats on the dragon's next death) and push the owner a fresh sync
            // so the client's binding/NBT aren't left stale.
            //
            // Verify-round polish #2: normalize sit/wander-target around the snapshot
            // exactly like DragonOwnerCapability#setDragonToWhistle does for every
            // OTHER whistle-bind snapshot — without this, a sitting/wandering original
            // gets snapshotted mid-sit/mid-wander, and any FUTURE snapshot-respawn of
            // this same dragon would mint it sitting.
            var cap = owner.getData(ModCapabilities.PLAYER_CAPABILITY);
            var wanderPos = original.getWanderTarget();
            var wasSitting = original.isOrderedToSit();
            original.setWanderTarget(Optional.empty());
            original.setOrderedToSit(false);
            var nbtData = original.serializeNBT(original.level.registryAccess());
            original.setWanderTarget(wanderPos);
            original.setOrderedToSit(wasSitting);
            cap.dragonNBTs.put(pending.index(), nbtData);
            cap.dragonInstances.put(pending.index(), new DragonInstance(original));
            cap.lastSummons.put(pending.index(), original.getUUID());

            if (owner instanceof ServerPlayer serverPlayer) {
                PacketDistributor.sendToPlayer(serverPlayer, new DragonNBTSync(pending.index(), nbtData));
                PacketDistributor.sendToPlayer(serverPlayer, new CompleteDataSync(owner));
            }

            DMR.LOGGER.info(
                    "Snapshot-clone reclaim: re-pointing whistle binding for dragonUUID {} (owner {}) at original"
                            + " entity {} in {} at ({}, {}, {}); discarding flagged clone {} in {} at ({}, {}, {})",
                    pending.dragonUUID(),
                    owner.getName().getString(),
                    original.getUUID(),
                    original.level().dimension().location(),
                    original.getX(),
                    original.getY(),
                    original.getZ(),
                    clone.getUUID(),
                    clone.level().dimension().location(),
                    clone.getX(),
                    clone.getY(),
                    clone.getZ());

            clone.discard();
        }
    }

    /**
     * Wave 5, Fix B4: detects a PLAUSIBLE snapshot-respawn clone and queues it for
     * verification (review fix #9 moved the actual proof/mutation/discard into
     * {@link #processPendingReclaims}, run on the next tick).
     *
     * <p>
     * Called from {@code DragonWhistleEvent#onEntityJoinWorld} whenever the existing
     * duplicate-dragon dedup check (a {@code lastSummons} mismatch) fires, and ONLY
     * when {@code duplicate_resolution=LOG} (review fix #7 — {@code OFF} means "never
     * touch duplicates" and must stay that way; {@code AGGRESSIVE} already cancels the
     * joining entity's own join unconditionally, and running reclaim first could
     * re-point the binding at that same soon-to-be-cancelled entity). Looks up the
     * OTHER live entity sharing this dragonUUID — the one the binding currently
     * expects — and, if EXACTLY ONE of the two carries the
     * {@code respawnedFromSnapshot} provenance flag, queues it as the candidate clone.
     * Zero or two flagged entities are not provably a clone situation and are left
     * entirely to the {@code duplicate_resolution=LOG} logging above — this never
     * guesses from binding staleness, which is the destructive {@code AGGRESSIVE} bug
     * this mechanism was built to avoid repeating.
     *
     * @param joiningDragon    the entity that just triggered the dedup mismatch
     * @param owner            the whistle owner
     * @param index            the mismatched whistle slot
     * @param expectedEntityId the entity UUID the binding's {@code lastSummons} points
     *                         at (the "other" entity to look up)
     */
    public static void maybeReclaimSnapshotClone(
            TameableDragonEntity joiningDragon, Player owner, int index, UUID expectedEntityId) {
        if (!ServerConfig.RECLAIM_SNAPSHOT_CLONES || joiningDragon.getDragonUUID() == null) {
            return;
        }

        var server = joiningDragon.getServer();
        if (server == null) {
            return;
        }

        TameableDragonEntity other = null;
        for (var level : server.getAllLevels()) {
            if (level.getEntity(expectedEntityId) instanceof TameableDragonEntity candidate
                    && joiningDragon.getDragonUUID().equals(candidate.getDragonUUID())) {
                other = candidate;
                break;
            }
        }

        if (other == null) {
            // The entity the binding expects isn't loaded anywhere right now — nothing
            // provable to reclaim.
            return;
        }

        boolean joiningFlagged = joiningDragon.isRespawnedFromSnapshot();
        boolean otherFlagged = other.isRespawnedFromSnapshot();

        if (joiningFlagged == otherFlagged) {
            // Zero or two flagged: can't prove which (if either) is the clone.
            return;
        }

        TameableDragonEntity clone = joiningFlagged ? joiningDragon : other;
        TameableDragonEntity original = joiningFlagged ? other : joiningDragon;

        PENDING_RECLAIMS.add(new PendingReclaim(
                owner.getUUID(),
                index,
                joiningDragon.getDragonUUID(),
                clone.level().dimension(),
                clone.getUUID(),
                original.level().dimension(),
                original.getUUID()));
    }

    /**
     * Wave 5 review fix #5: {@link #DEFERRED_SUMMONS} and {@link #PENDING_RECLAIMS}
     * hold absolute {@code server.getTickCount()}-based deadlines. That counter resets
     * to zero on every new integrated-server session, so an entry left over from a
     * previous session (e.g. the player quit mid-deferral) would have an already-past
     * "deadline" the moment the world loads again — silently firing a snapshot respawn
     * or a clone reclaim the player never actually triggered this session. Call from
     * {@code ServerStoppingEvent} to guarantee neither queue survives a server stop.
     *
     * <p>
     * Verify-round polish #5: also clears {@link #WARNED_MALFORMED_DIMENSIONS} — not
     * itself a correctness issue (it only suppresses a log line), but there is no
     * reason for a log-dedup set to outlive the session it was deduplicating within,
     * and a fresh session deserves a fresh first warning if the same bad data is still
     * there.
     */
    public static void clearTransientState() {
        DEFERRED_SUMMONS.clear();
        PENDING_RECLAIMS.clear();
        WARNED_MALFORMED_DIMENSIONS.clear();
    }
}
