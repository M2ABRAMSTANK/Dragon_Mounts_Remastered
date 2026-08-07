package dmr.DragonMounts.common.handlers;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.ModConstants;
import dmr.DragonMounts.common.capability.DragonOwnerCapability;
import dmr.DragonMounts.common.capability.types.NBTInterface;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.network.packets.CompleteDataSync;
import dmr.DragonMounts.network.packets.DragonStatePacket;
import dmr.DragonMounts.registry.ModCapabilities;
import dmr.DragonMounts.registry.ModEntities;
import dmr.DragonMounts.registry.ModItems;
import dmr.DragonMounts.registry.ModSounds;
import dmr.DragonMounts.server.entity.DragonConstants;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.items.DragonWhistleItem;
import dmr.DragonMounts.util.PlayerStateUtils;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
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

    public static void summonDragon(Player player) {
        if (player != null) {
            if (callDragon(player)) {
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
        }
    }

    /**
     * How long (in ticks) a deferred summon keeps re-checking for the ticketed chunk's
     * dragon before treating it as truly absent. Chunk loading is asynchronous and can
     * take more than a couple of ticks — giving up after a single fixed-delay re-check
     * would fall back to the snapshot respawn while the real dragon is mid-load, which
     * is exactly the clone bug this wave removes (#125).
     */
    private static final int DEFERRED_SUMMON_TIMEOUT_TICKS = 20;

    private record DeferredSummon(UUID playerId, int index, int deadlineTick) {}

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
                var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(instance.getDimension()));
                var storedLevel = serverPlayer.server.getLevel(key);

                if (storedLevel != null) {
                    boolean alreadyPending = DEFERRED_SUMMONS.stream()
                            .anyMatch(pending -> pending.playerId().equals(serverPlayer.getUUID())
                                    && pending.index() == summonItemIndex);
                    if (!alreadyPending) {
                        var chunkPos = new ChunkPos(instance.getLastPos());
                        // Hold the chunk (POST_TELEPORT, 5-tick lifespan, refreshed by the
                        // deferred poll below) and load it SYNCHRONOUSLY: the blocking
                        // getChunk drives the chunk system on the server thread, and full
                        // promotion flips the chunk's parked entity sections to visible
                        // (PersistentEntitySectionManager.updateChunkStatus), so the
                        // dragon usually becomes resolvable immediately.
                        storedLevel
                                .getChunkSource()
                                .addRegionTicket(TicketType.POST_TELEPORT, chunkPos, 2, serverPlayer.getId());
                        storedLevel.getChunk(chunkPos.x, chunkPos.z);
                        // Bounded real-time block (see drainChunkTasks javadoc) — this is
                        // the primary resolution path; a couple hundred ms once, on the
                        // rare cross-dimension summon, beats a 20-tick race that sometimes
                        // clones the dragon.
                        drainChunkTasks(storedLevel, () -> findDragon(player, summonItemIndex) != null, 250);

                        var reChecked = findDragon(player, summonItemIndex);
                        if (reChecked != null) {
                            return summonExistingDragon(player, cap, summonItemIndex, reChecked);
                        }

                        // Still unresolved — entity data can stream in from disk
                        // asynchronously after the block chunk loads. Poll for a bounded
                        // number of ticks before treating the dragon as absent.
                        DEFERRED_SUMMONS.add(new DeferredSummon(
                                serverPlayer.getUUID(),
                                summonItemIndex,
                                serverPlayer.server.getTickCount() + DEFERRED_SUMMON_TIMEOUT_TICKS));
                        DMR.LOGGER.debug(
                                "Dragon {} not loaded; ticketed chunk {} in {} and deferred the summon for {}",
                                instance.getUUID(),
                                chunkPos,
                                instance.getDimension(),
                                player.getName().getString());
                    }
                    return true;
                }
            }

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

        for (DeferredSummon pending : DEFERRED_SUMMONS) {
            var player = server.getPlayerList().getPlayer(pending.playerId());
            if (player == null) {
                DEFERRED_SUMMONS.remove(pending);
                continue; // Owner logged off while the summon was pending; drop it.
            }

            var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);

            // Keep the region ticket alive (POST_TELEPORT's lifespan is only 5 ticks)
            // and pump the stored level's chunk tasks so pending status promotions can
            // land before the re-check below.
            var instance = cap.dragonInstances.get(pending.index());
            if (instance != null && instance.getLastPos() != null && instance.getDimension() != null) {
                var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(instance.getDimension()));
                var storedLevel = server.getLevel(key);
                if (storedLevel != null) {
                    storedLevel
                            .getChunkSource()
                            .addRegionTicket(
                                    TicketType.POST_TELEPORT, new ChunkPos(instance.getLastPos()), 2, player.getId());
                    // Short budget here: this path already gets another attempt every tick
                    // for up to DEFERRED_SUMMON_TIMEOUT_TICKS ticks, so a genuinely cold
                    // (disk-backed) chunk gets many short chances rather than one long one.
                    drainChunkTasks(storedLevel, () -> findDragon(player, pending.index()) != null, 50);
                }
            }

            var dragon = findDragon(player, pending.index());

            if (dragon != null) {
                DEFERRED_SUMMONS.remove(pending);
                summonExistingDragon(player, cap, pending.index(), dragon);
            } else if (server.getTickCount() >= pending.deadlineTick()) {
                DEFERRED_SUMMONS.remove(pending);
                respawnDragonFromSnapshot(player, cap, pending.index());
            }
        }
    }

    /**
     * Blocks the server thread — bounded by {@code budgetMillis} of real wall-clock time —
     * pumping the given level's chunk-ticket machinery until {@code resolved} is satisfied.
     *
     * <p>
     * Loading a chunk's TERRAIN ({@code ServerLevel#getChunk}, called before this) does
     * NOT by itself make that chunk's entities resolvable: {@code ServerLevel#getEntity}
     * and area-based entity queries alike are gated on the chunk's {@code FullChunkStatus}
     * reaching {@code TRACKED}/{@code ENTITY_TICKING}
     * ({@code PersistentEntitySectionManager#updateChunkStatus} only promotes a chunk's
     * entity sections out of {@code HIDDEN} at that point — confirmed against decompiled
     * 1.21.1 sources: {@code EntityLookup}'s UUID/id maps AND
     * {@code EntitySectionStorage}'s AABB-bounded queries both filter on
     * {@code Visibility#isAccessible()}). That promotion is driven by
     * {@code DistanceManager}'s ticket-level BFS and {@code ChunkHolder} status-future
     * resolution, whose completion is posted back from a background thread pool
     * ({@code Util.backgroundExecutor()}) — real concurrency, not just queued work waiting
     * for the main thread. A tight same-tick loop that only calls
     * {@code ServerChunkCache#tick}/{@code pollTask} back-to-back never actually cedes the
     * CPU, so the background thread may not get a chance to run before the loop gives up —
     * this made the caller's gametest pass only when unrelated logging slowed the main
     * thread down (incidentally giving the background executor real time to catch up) and
     * fail at full speed. Mirrors vanilla's own blocking pattern for exactly this situation
     * ({@code BlockableEventLoop#managedBlock}/{@code waitForTasks}: poll, then
     * {@code LockSupport.parkNanos} briefly so another thread can actually run) rather than
     * waiting on a fixed tick-count budget that races the executor instead of yielding to
     * it. {@code tickChunks=false} deliberately skips the block/entity ticking half of
     * {@code ServerChunkCache#tick} — this call must not double-tick gameplay in the stored
     * dimension out of band from the normal tick loop.
     *
     * <p>
     * Deliberately calls {@code ServerChunkCache#tick} (which is what actually schedules
     * the {@code FullChunkStatus} promotion futures, via
     * {@code DistanceManager#runAllUpdates}) exactly ONCE, not on every iteration of the
     * wait loop: {@code ServerChunkCache#tick} starts by calling
     * {@code DistanceManager#purgeStaleTickets}, which decrements every non-persistent
     * ticket's remaining lifespan (including the caller's short-lived
     * {@code TicketType.POST_TELEPORT} ticket) by one EVERY TIME IT RUNS — it is meant to
     * be called once per REAL server tick. Calling it hundreds of times back-to-back in a
     * tight loop (an earlier version of this method did) burns through that ticket's
     * lifespan almost instantly and expires it before its promotion future ever resolves,
     * which pulls the chunk's ticket level back down and re-triggers a demotion — a
     * self-defeating loop that can never converge. The promotion futures scheduled by the
     * single {@code tick()} call below (and by the {@code ServerLevel#getChunk} call the
     * caller already made) complete on a background thread pool; only {@code pollTask} is
     * needed afterward to run their completion callbacks once they land.
     */
    private static void drainChunkTasks(ServerLevel level, BooleanSupplier resolved, long budgetMillis) {
        var chunkSource = level.getChunkSource();
        chunkSource.tick(() -> true, false);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        while (!resolved.getAsBoolean() && System.nanoTime() < deadline) {
            boolean ranAny = false;
            int taskBudget = 1000;
            while (taskBudget-- > 0 && chunkSource.pollTask()) {
                ranAny = true;
            }

            if (!ranAny) {
                // Nothing was ready to run on the main thread — give the background chunk
                // executor a real (if brief) slice of wall-clock time before polling again,
                // instead of spinning and starving it entirely.
                LockSupport.parkNanos(100_000L);
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
     * LAST RESORT (Wave 2): respawn the dragon from its NBT snapshot. This is the single
     * choke point every "the dragon didn't resolve" path funnels into — the deferred
     * (chunk-ticketed) summon's timeout in {@link #processDeferredSummons} and the
     * legacy-binding fallback in {@link #callDragon} both land here — so the confirmation
     * gate below is enforced exactly once, for everyone.
     *
     * <p>
     * Three hard gates before ever minting a clone (fix-plan Wave 2 item 2 / BRIEF Bug 2
     * fix-path 1: "do not treat 'not currently loaded' as 'does not exist'"):
     * {@code allow_respawn} must be enabled; the dragon's stored chunk must have
     * positively reached {@code ENTITY_TICKING} status (not merely "we waited a while" or
     * "the terrain loaded") so that an empty entity read there actually means something;
     * and an all-levels existence re-check must come back empty.
     *
     * <p>
     * A timed-out deferred summon and a chunk-ticket-less legacy binding both used to
     * fall through this method's predecessor straight into a clone: a 20-tick deadline is
     * not proof a cold-region read or a saturated chunk pipeline actually finished, and a
     * legacy binding with no recorded {@code lastPos} can't even be ticketed to check.
     * Both are now refused (not_found, recoverable — the player can just call again) —
     * because {@code duplicate_resolution} defaults to LOG (never removes) as of this
     * wave, a wrongly-minted clone here is permanent, not recoverable.
     */
    private static boolean respawnDragonFromSnapshot(Player player, DragonOwnerCapability cap, int summonItemIndex) {
        // The binding can be cleaned up between a deferred summon's scheduling and its
        // re-check (canCall's invalid-data sweep, dragon death without respawn, ...);
        // createDragonEntity dereferences the instance, so bail here instead.
        var instance = cap.getDragonInstance(summonItemIndex);
        if (instance == null || cap.dragonNBTs.get(summonItemIndex) == null) {
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
        // whether or not the dragon is actually sitting in it. A 20-tick deferred-summon
        // deadline is a budget, not a promise that promotion finished; a legacy binding
        // with no recorded lastPos can't even be pointed at a chunk to check. Either way,
        // without positive proof, refuse rather than guess — the binding regains a usable
        // lastPos the next time its dragon is naturally sighted (TameableDragonEntity's
        // periodic baseTick refresh or its next changeDimension), at which point a retry
        // can actually confirm absence.
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

        var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(instance.getDimension()));
        var storedLevel = server.getLevel(key);
        var chunkPos = new ChunkPos(instance.getLastPos());

        // Block status and entity data load through SEPARATE async pipelines in 1.21.1:
        // isPositionTicking can be true while PersistentEntitySectionManager is still
        // reading the chunk's entities/ region file (the read resolves in
        // entityManager.tick(), strictly after promotion). Vanilla's own guard for
        // "safe to trust an empty entity lookup" is both conjuncts — see
        // ServerLevel#isPositionTickingWithEntitiesLoaded. Without areEntitiesLoaded,
        // a slow chunk load lets an empty lookup mint a snapshot clone of a dragon
        // that is actually still on disk.
        if (storedLevel == null
                || !storedLevel.areEntitiesLoaded(chunkPos.toLong())
                || !storedLevel.getChunkSource().isPositionTicking(chunkPos.toLong())) {
            DMR.LOGGER.warn(
                    "Refusing to respawn dragon {} for player {} from snapshot: its stored chunk {} in {} has not"
                            + " reached ticking status — an empty read there proves nothing, only that loading"
                            + " isn't finished; try the summon again",
                    instance.getUUID(),
                    player.getName().getString(),
                    chunkPos,
                    instance.getDimension());
            if (!player.level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
            }
            return false;
        }

        // Gate 2: now that the chunk the binding actually points at is confirmed to have
        // finished loading, an empty existence read is trustworthy. Still sweep ALL
        // levels (not just storedLevel) — this is the belt-and-suspenders that catches
        // the binding's recorded dimension itself being wrong (e.g. a third-party mod
        // teleport that findDragon never looked at), same as before.
        for (var candidateLevel : server.getAllLevels()) {
            var liveMatches = candidateLevel.getEntities(
                    ModEntities.DRAGON_ENTITY.get(),
                    (TameableDragonEntity candidate) -> instance.getUUID().equals(candidate.getDragonUUID()));
            if (!liveMatches.isEmpty()) {
                // A live dragon with this dragonUUID exists somewhere. Minting a clone
                // here would duplicate it — abort instead (#125).
                DMR.LOGGER.warn(
                        "Aborting snapshot respawn of dragon {} for player {}: a live entity with this"
                                + " dragonUUID was found in {} at {} — the binding's recorded dimension ({})"
                                + " was stale, not the dragon actually being absent",
                        instance.getUUID(),
                        player.getName().getString(),
                        candidateLevel.dimension().location(),
                        liveMatches.get(0).blockPosition(),
                        instance.getDimension());
                if (!player.level.isClientSide) {
                    player.displayClientMessage(
                            Component.translatable("dmr.dragon_call.not_found").withStyle(ChatFormatting.RED), true);
                }
                return false;
            }
        }

        TameableDragonEntity newDragon = cap.createDragonEntity(player, player.level, summonItemIndex);

        if (newDragon == null) {
            return false;
        }

        DMR.LOGGER.warn(
                "Respawning dragon: {} from snapshot for player: {} — no live entity found in its stored dimension",
                newDragon.getDragonUUID(),
                player.getName().getString());

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
            var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(instance.getDimension()));
            var storedLevel = server.getLevel(key);

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
                // callDragon/processDeferredSummons force synchronously via
                // drainChunkTasks() before calling in here. Area-bounded entity queries
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
}
