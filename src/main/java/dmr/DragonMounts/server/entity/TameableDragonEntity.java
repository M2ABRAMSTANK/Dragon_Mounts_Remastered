package dmr.DragonMounts.server.entity;

import com.mojang.serialization.Dynamic;
import dmr.DragonMounts.DMR;
import dmr.DragonMounts.ModConstants;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler.DragonInstance;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.registry.ModCriterionTriggers;
import dmr.DragonMounts.server.ai.DragonAI;
import dmr.DragonMounts.server.entity.dragon.AbstractDragonEntity;
import dmr.DragonMounts.server.inventory.DragonInventoryHandler.DragonInventory;
import dmr.DragonMounts.util.PlayerStateUtils;
import java.util.Optional;
import lombok.Getter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.Brain.Provider;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SaddleItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import org.jetbrains.annotations.Nullable;

@Getter
public class TameableDragonEntity extends AbstractDragonEntity {

    public TameableDragonEntity(EntityType<? extends TamableAnimal> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    @Override
    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        return DragonAI.makeBrain(
                (Brain<TameableDragonEntity>) this.brainProvider().makeBrain(dynamic));
    }

    @Override
    protected void customServerAiStep() {
        this.level().getProfiler().push("dragonBrain");
        this.getBrain().tick((ServerLevel) this.level, this);
        this.level().getProfiler().pop();
        this.level().getProfiler().push("dragonActivityUpdate");
        DragonAI.selectMostAppropriateActivity(this);
        this.level().getProfiler().pop();
        super.customServerAiStep();
    }

    @Override
    public Brain<TameableDragonEntity> getBrain() {
        return (Brain<TameableDragonEntity>) super.getBrain();
    }

    @Override
    protected Provider<?> brainProvider() {
        return DragonAI.brainProvider();
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        // B3: hatched dragons are player-created and must never distance-despawn.
        // wasHatched() previously sat in the OR below, which made despawn MORE likely.
        if (wasHatched()) {
            return false;
        }

        // Note: the parameter is a SQUARED distance (vanilla passes distanceToSqr), so the
        // old `> Mth.sqrt(32)` compared d^2 against ~5.7 (i.e. ~2.4 blocks). Compare
        // squared-vs-squared for the intended 32-block threshold.
        return ((this.tickCount > 2400 || isNaturalSpawn())
                && !isTame()
                && distanceToClosestPlayer > 32 * 32
                && !this.hasCustomName());
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();

        if (!this.level.isClientSide) {
            reconcileEquipmentFlagsWithContainer();
        }
    }

    /**
     * Wave 3, cheap defense (mostly moot after Wave 2's globalized inventory store —
     * kept as a backstop): the saddle/chest SynchedEntityData flags travel in entity
     * NBT, but the actual container contents live in DragonWorldData's global
     * inventory store, keyed separately by dragonUUID. If the two ever disagree — a
     * stray legacy per-dimension entry that missed migration, a third party clearing
     * the container without touching the dragon, hand-edited NBT — a flag stuck
     * {@code true} makes the client keep rendering equipment (and keep the inventory
     * unlocked) that no longer exists (upstream #98's "model shows saddle, items
     * gone"). Trust the CONTAINER, correct the flags to match it, and log once so an
     * operator can see it happened instead of silently living with stale flags.
     */
    private void reconcileEquipmentFlagsWithContainer() {
        var inventory = getDragonInventory();
        if (inventory == null) return;

        boolean flaggedSaddled = entityData.get(saddledDataAccessor);
        boolean actuallySaddled =
                inventory.inventory.getItem(DragonInventory.SADDLE_SLOT).is(Items.SADDLE);

        boolean flaggedChest = entityData.get(idChestDataAccessor);
        var chestItem = inventory.inventory.getItem(DragonInventory.CHEST_SLOT);
        boolean actuallyChested = chestItem.is(Items.CHEST) || chestItem.is(Items.ENDER_CHEST);

        if (flaggedSaddled != actuallySaddled || flaggedChest != actuallyChested) {
            DMR.LOGGER.warn(
                    "Dragon {} equipment flags disagreed with its actual inventory contents on load"
                            + " (saddled flag={} actual={}, chest flag={} actual={}) — correcting flags to"
                            + " match the container.",
                    getDragonUUID(),
                    flaggedSaddled,
                    actuallySaddled,
                    flaggedChest,
                    actuallyChested);

            updateContainerEquipment();
        }
    }

    @Override
    protected Component getTypeName() {
        if (hasVariant()) {
            return Component.translatable(
                    DMR.MOD_ID + ".dragon_breed." + getBreed().getId() + ModConstants.VARIANT_DIVIDER + getVariantId());
        }

        return getBreed().getName();
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);

        var stackResult = stack.interactLivingEntity(player, this, hand);
        if (stackResult.consumesAction()) return stackResult;

        // tame
        if (!isTame()) {
            if (isServer() && isTamingItem(stack)) {
                stack.shrink(1);
                var shouldTame = getRandom().nextInt(5) == 0;
                tamedFor(player, shouldTame);

                if (shouldTame) {
                    if (player instanceof ServerPlayer serverPlayer) {
                        ModCriterionTriggers.TAME_DRAGON.get().trigger(serverPlayer);
                    }
                }

                return InteractionResult.SUCCESS;
            }

            return InteractionResult.PASS; // pass regardless. We don't want to perform breeding, age ups, etc. on
            // untamed.
        }

        // heal
        if ((getHealthRelative() < 1 && getHealth() < (getMaxHealth() - 1)) && isFoodItem(stack)) {
            // noinspection ConstantConditions
            heal(stack.getItem().getFoodProperties(stack, this).nutrition());
            playSound(getEatingSound(stack), 0.7f, 1);
            stack.shrink(1);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // saddle up!
        if (isTamedFor(player) && isSaddleable() && !isSaddled() && stack.getItem() instanceof SaddleItem) {
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            equipSaddle(stack, getSoundSource());
            updateContainerEquipment();
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // equip armor
        if (isTamedFor(player) && isArmor(stack)) {
            equipArmor(player, stack);
            updateContainerEquipment();
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (isTamedFor(player) && !hasChest() && stack.is(Items.CHEST)) {
            this.getInventory().setItem(DragonInventory.CHEST_SLOT, stack.copyWithCount(1));
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            updateContainerEquipment();
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // open menu
        if (isTamedFor(player) && player.isSecondaryUseActive()) {
            if (!level.isClientSide) this.openCustomInventoryScreen(player);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // ride on
        if (isTamedFor(player) && isSaddled() && !isHatchling() && !isFood(stack)) {
            if (isServer()) {
                setRidingPlayer(player);
                navigation.stop();
            }
            setTarget(null);
            setWanderTarget(Optional.empty());
            stopSitting();
            getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            updateOwnerData();
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        return super.mobInteract(player, hand);
    }

    @Override
    public void baseTick() {
        super.baseTick();

        if (!this.level.isClientSide && this.isAlive() && this.tickCount % 20 == 0) {
            this.heal((float) ServerConfig.HEALTH_REGEN);
        }

        // Wave 2 follow-up (code-investigation.md defect: "DragonInstance.lastPos is
        // stale by construction"): lastPos was previously only written at bind time and
        // on changeDimension, so a dragon that stays in one dimension but wanders away
        // from wherever it was last written (owner travels/waystones off, dragon's chunk
        // unloads elsewhere) left the whistle summon path ticketing/searching a stale
        // chunk. Refresh periodically while tamed — cheap at this cadence, and exactly
        // the data the summon path needs to have correct when the dragon's chunk isn't
        // currently loaded (upstream #124/#125).
        if (!this.level.isClientSide && this.isAlive() && this.isTame() && this.tickCount % 100 == 0) {
            var owner = resolveOwnerServerWide();
            if (owner != null) {
                var summonIndex = DragonWhistleHandler.getDragonSummonIndex(owner, getDragonUUID());
                if (summonIndex.isPresent()) {
                    PlayerStateUtils.getHandler(owner)
                            .setDragonInstance(summonIndex.getAsInt(), new DragonInstance(this));
                }
            }
        }

        if (getDragonInventory() != null && getDragonInventory().isDirty()) {
            updateContainerEquipment();
        }
    }

    @Override
    protected void sendDebugPackets() {
        super.sendDebugPackets();
        DebugPackets.sendEntityBrain(this);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        if (DATA_FLAGS_ID.equals(data)) {
            refreshDimensions();
        } else {
            super.onSyncedDataUpdated(data);
        }
    }

    @Override
    public @Nullable Entity changeDimension(DimensionTransition transition) {
        var entity = super.changeDimension(transition);

        if (entity instanceof TameableDragonEntity dragon) {
            DMR.LOGGER.debug(
                    "Changing dimension of dragon {} to {}",
                    getDragonUUID(),
                    transition.newLevel().dimension().location());

            // Wave 2: resolve the owner server-wide, NEVER via the level-scoped
            // getOwner(). During ridden transits passengers move first, and on
            // Nether->Overworld returns the owner is not in the dragon's (old) level;
            // both made getOwner() return null and left DragonInstance.dimension stale
            // (the "directional rot", .fork-notes/code-investigation.md) — the root of
            // the #123/#98 clone-and-delete cascades.
            var owner = resolveOwnerServerWide();

            if (owner != null) {
                var handler = PlayerStateUtils.getHandler(owner);
                var summonIndex = DragonWhistleHandler.getDragonSummonIndex(owner, getDragonUUID());

                // Unbound dragons have no whistle binding to update — the old .orElse(0)
                // fallback wrote their instance into slot 0 and condemned that slot's
                // bound dragon to the dedup check.
                if (summonIndex.isPresent()) {
                    var index = summonIndex.getAsInt();
                    // Always refresh the binding: dimension + lastPos from the arrival
                    // level (the new DragonInstance reads them off the arrived entity).
                    handler.setDragonInstance(index, new DragonInstance(dragon));

                    // Update lastSummon to the arrived entity's UUID to prevent despawns;
                    // conditioned on this dragon actually being the bound one.
                    if (handler.lastSummons.get(index) != null
                            && handler.lastSummons.get(index).equals(getUUID())) {
                        handler.lastSummons.put(index, entity.getUUID());
                    }
                }
            }
            // Owner offline: NeoForge data attachments live on the loaded Player entity;
            // an offline owner's attachment is not loaded and cannot be safely rewritten
            // from here. The binding is reconciled on the owner's next login instead
            // (PlayerJoinWorld.onPlayerJoinWorld), and the summon path's chunk-ticket
            // re-check tolerates a stale dimension in the meantime.

            // NOTE (Wave 2, B1): the manual inventory hand-off that used to live here is
            // gone — dragon inventories are stored once, globally, on the overworld
            // (DragonInventoryHandler.getOrCreateInventory), so a dimension change no
            // longer moves any inventory entry.

            return dragon;
        }

        return null;
    }

    /**
     * Resolves this dragon's owner across the whole server: the player list first, then
     * every level's player list (gametest mock players are ticked in a level without
     * being registered in the server player list).
     */
    private @Nullable Player resolveOwnerServerWide() {
        var ownerId = getOwnerUUID();
        var server = getServer();

        if (ownerId == null || server == null) {
            return null;
        }

        Player owner = server.getPlayerList().getPlayer(ownerId);
        if (owner != null) {
            return owner;
        }

        for (var serverLevel : server.getAllLevels()) {
            for (var candidate : serverLevel.players()) {
                if (candidate.getUUID().equals(ownerId)) {
                    return candidate;
                }
            }
        }

        return null;
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (!this.isInWater() || !this.canDrownInFluidType(Fluids.WATER.getFluidType())) {
            super.travel(travelVector);
            return;
        }

        double y0 = this.getY();
        float waterFriction = this.isSprinting() ? 0.7F : 0.5F;
        float inWaterSpeedModifier = (float) this.getAttributeValue(NeoForgeMod.SWIM_SPEED);

        this.moveRelative(inWaterSpeedModifier, travelVector);
        this.move(MoverType.SELF, this.getDeltaMovement());

        Vec3 deltaVector = this.getDeltaMovement();

        if (this.horizontalCollision && this.onClimbable()) {
            deltaVector = new Vec3(deltaVector.x, 0.2, deltaVector.z);
        }

        Vec3 waterDragVector = new Vec3(
                deltaVector.x * waterFriction * 0.6D,
                deltaVector.y * getWaterSlowDown(),
                deltaVector.z * waterFriction * 0.6D);

        this.setDeltaMovement(waterDragVector);
        Vec3 nextDeltaVector = this.getDeltaMovement();
        if (this.horizontalCollision
                && this.isFree(nextDeltaVector.x, nextDeltaVector.y + 0.6D - this.getY() + y0, nextDeltaVector.z)) {
            this.setDeltaMovement(nextDeltaVector.x, 0.3D, nextDeltaVector.z);
        }
    }
}
