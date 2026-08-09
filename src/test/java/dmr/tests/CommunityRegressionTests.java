package dmr.tests;

import dmr.DMRTestConstants;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.network.packets.CompleteDataSync;
import dmr.DragonMounts.registry.DragonBreedsRegistry;
import dmr.DragonMounts.registry.ModCapabilities;
import dmr.DragonMounts.registry.ModEntities;
import dmr.DragonMounts.registry.ModItems;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.inventory.DragonInventoryHandler.DragonInventory;
import dmr.DragonMounts.server.items.DragonWhistleItem;
import dmr.DragonMounts.server.worlddata.DragonWorldData;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/**
 * Executable specification for the 1.9.2 community-fork defect classes (Wave 0).
 *
 * <p>
 * Every test in this class that is marked {@code required = false} is expected to be
 * RED on the unmodified 1.9.2 baseline: each one reproduces a defect documented in
 * {@code .fork-notes/code-investigation.md} and is flipped to required in the same
 * commit as its fix (see {@code .fork-notes/fix-plan.md} for the wave plan).
 */
@PrefixGameTestTemplate(false)
@ForEachTest(groups = "Community Regressions")
public class CommunityRegressionTests {

    /**
     * Meta-test: asserts that the gametest classpath is intact.
     *
     * <p>
     * DMR.java swallows test-framework registration failures (reflective lookup of
     * dmr.DMRTestMod), so a classpath break can silently register far fewer tests and
     * let CI go green having verified nothing. This REQUIRED test fails any run where
     * fewer than 50 test functions are registered.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate
    @GameTest
    @TestHolder
    public static void gameTestRegistrationFloor(ExtendedGameTestHelper helper) {
        int count = GameTestRegistry.getAllTestFunctions().size();
        if (count < 50) {
            helper.fail("Only " + count + " gametests are registered (floor is 50) — the test classpath is broken");
        }
        helper.succeed();
    }

    /**
     * Tests that riding a bound dragon through a dimension change keeps the whistle
     * binding's DragonInstance pointing at the dragon's actual dimension.
     *
     * <p>
     * Baseline defect: vanilla changeDimension moves passengers FIRST, so when the
     * dragon's changeDimension override runs, the owner is no longer in the dragon's
     * (old) level and the level-scoped getOwner() returns null — the DragonInstance
     * dimension update is skipped and the binding rots (root of #123/#98 cascades).
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void riddenPortalTransitUpdatesInstance(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var dragonUuid = dragon.getDragonUUID();
        DragonWhistleHandler.setDragon(player, dragon, 0);

        // Saddle and mount the dragon (same pattern as DragonTests.rideDragon)
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SADDLE));
        dragon.interact(player, InteractionHand.MAIN_HAND);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        dragon.interact(player, InteractionHand.MAIN_HAND);

        if (!player.isPassenger()) {
            helper.fail("Player is not riding dragon");
            return;
        }

        var server = helper.getLevel().getServer();
        var netherDim = server.getLevel(Level.NETHER);
        if (netherDim == null) {
            helper.fail("Nether level is not available");
            return;
        }

        // Ride through the portal: vanilla transfers the passenger (player) first,
        // then the vehicle (dragon).
        var transition = new DimensionTransition(
                netherDim, new Vec3(0, 100, 0), new Vec3(0, 0, 0), 0, 0, true, DimensionTransition.DO_NOTHING);
        var result = dragon.changeDimension(transition);

        if (result == null) {
            helper.fail("changeDimension returned null for the ridden dragon");
            return;
        }

        if (!result.level().dimension().equals(Level.NETHER)) {
            helper.fail("Dragon did not arrive in the Nether: " + result.level().dimension());
            return;
        }

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        var instance = cap.dragonInstances.get(0);

        if (instance == null) {
            helper.fail("Whistle binding was lost during ridden dimension transit");
            return;
        }

        if (!instance.getUUID().equals(dragonUuid)) {
            helper.fail("Whistle binding points at a different dragon after ridden dimension transit");
            return;
        }

        var expectedDimension = Level.NETHER.location().toString();
        if (!expectedDimension.equals(instance.getDimension())) {
            helper.fail("DragonInstance.dimension was not updated on ridden portal transit: expected "
                    + expectedDimension + " but got " + instance.getDimension());
            return;
        }

        helper.succeed();
    }

    /**
     * Tests that an owned-but-UNBOUND dragon survives a chunk reload while another
     * dragon occupies whistle slot 0.
     *
     * <p>
     * Baseline defect: getDragonSummonIndex(Player, UUID) returns
     * {@code .orElse(0)} for unbound dragons, so the EntityJoinLevelEvent dedup check
     * compares the unbound dragon against slot 0's lastSummons entry and deletes it
     * (the ".orElse(0) slot-0 landmine", defect 2 in code-investigation.md).
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void unboundDragonSurvivesChunkReload(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Owned but NOT bound to any whistle
        var unboundDragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        unboundDragon.setBreed(DragonBreedsRegistry.getDefault());
        unboundDragon.tamedFor(player, true);

        // A different dragon bound to whistle slot 0 populates lastSummons[0]
        var boundDragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS.offset(2, 0, 0));
        boundDragon.setBreed(DragonBreedsRegistry.getDefault());
        boundDragon.tamedFor(player, true);
        DragonWhistleHandler.setDragon(player, boundDragon, 0);

        var dragonUuid = unboundDragon.getDragonUUID();

        // Simulate a chunk reload of the unbound dragon: serialize, remove, re-add.
        // Re-adding runs the real EntityJoinLevelEvent path including DMR's dedup cancel.
        var tag = new CompoundTag();
        if (!unboundDragon.save(tag)) {
            helper.fail("Failed to serialize the unbound dragon");
            return;
        }
        unboundDragon.discard();

        var reloaded = EntityType.loadEntityRecursive(tag, helper.getLevel(), entity -> entity);
        if (reloaded == null) {
            helper.fail("Failed to deserialize the unbound dragon");
            return;
        }

        boolean added = helper.getLevel().addFreshEntity(reloaded);
        if (!added) {
            helper.fail("Unbound dragon was deleted on chunk reload (slot-0 lastSummons landmine)");
            return;
        }

        var survivors = helper.getLevel()
                .getEntities(ModEntities.DRAGON_ENTITY.get(), entity -> dragonUuid.equals(entity.getDragonUUID()));
        if (survivors.isEmpty()) {
            helper.fail("Unbound dragon no longer exists in the level after chunk reload");
            return;
        }

        helper.succeed();
    }

    /**
     * Tests that summoning a dragon from a snapshot whose attribute tags are missing
     * does not pin the dragon's rolled stats to the minimum bound.
     *
     * <p>
     * Baseline defect: DragonAttributeComponent.readAdditionalSaveData reads the four
     * attribute tags unguarded — a missing tag yields 0.0f, which maps to the LOWEST
     * possible stat roll (matches #127/#128's "lowest stats" reports).
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void snapshotMissingAttributeTagsDoesNotPinStats(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        // Deterministic, clearly-above-minimum rolls
        dragon.getEntityData().set(TameableDragonEntity.healthAttribute, 0.75f);
        dragon.getEntityData().set(TameableDragonEntity.speedAttribute, 0.75f);
        dragon.getEntityData().set(TameableDragonEntity.damageAttribute, 0.75f);
        dragon.getEntityData().set(TameableDragonEntity.maxScaleAttribute, 0.75f);

        DragonWhistleHandler.setDragon(player, dragon, 1);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        var nbt = cap.dragonNBTs.get(1);
        if (nbt == null) {
            helper.fail("Dragon NBT snapshot missing after binding");
            return;
        }

        // Corrupt the snapshot the way legacy/partial data does: drop the attribute tags
        nbt.remove("healthAttribute");
        nbt.remove("speedAttribute");
        nbt.remove("damageAttribute");
        nbt.remove("maxScaleAttribute");

        var summoned = cap.createDragonEntity(player, player.level, 1);
        if (summoned == null) {
            helper.fail("Failed to create dragon from snapshot");
            return;
        }

        if (summoned.getEntityData().get(TameableDragonEntity.healthAttribute) <= 0.0f
                || summoned.getEntityData().get(TameableDragonEntity.speedAttribute) <= 0.0f
                || summoned.getEntityData().get(TameableDragonEntity.damageAttribute) <= 0.0f
                || summoned.getEntityData().get(TameableDragonEntity.maxScaleAttribute) <= 0.0f) {
            helper.fail("Summoned dragon's stats were pinned to the minimum bound because the snapshot"
                    + " was missing attribute tags");
            return;
        }

        helper.succeed();
    }

    /**
     * Tests that dead-dragon respawn state survives a DragonWorldData save()/load()
     * round-trip.
     *
     * <p>
     * Baseline defect: DragonWorldData.save writes the per-dragon uuid/delay/message
     * fields onto the OUTER tag and appends an empty compound to the list, so
     * deadDragons/deathDelay/deathMessages never survive a server restart.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate
    @GameTest
    @TestHolder
    public static void deadDragonStateSurvivesSaveLoad(ExtendedGameTestHelper helper) {
        var provider = helper.getLevel().registryAccess();

        var data = new DragonWorldData();
        UUID deadDragonId = UUID.randomUUID();
        data.deadDragons.add(deadDragonId);
        data.deathDelay.put(deadDragonId, 1200);
        data.deathMessages.put(deadDragonId, "test-death-message");

        var savedTag = data.save(new CompoundTag(), provider);
        var loaded = DragonWorldData.load(savedTag, provider);

        if (loaded.deadDragons.isEmpty() || !loaded.deadDragons.contains(deadDragonId)) {
            helper.fail("Dead-dragon list did not survive a save/load round-trip");
            return;
        }

        if (loaded.deathDelay.getOrDefault(deadDragonId, 0) != 1200) {
            helper.fail("Dead-dragon respawn delay did not survive a save/load round-trip");
            return;
        }

        if (!"test-death-message".equals(loaded.deathMessages.get(deadDragonId))) {
            helper.fail("Dead-dragon death message did not survive a save/load round-trip");
            return;
        }

        helper.succeed();
    }

    /**
     * Tests that a dragon's chest inventory survives TWO consecutive cross-dimension
     * summons (advisor ruling B1's regression net).
     *
     * <p>
     * Baseline defect chain: a Nether-to-Overworld transit runs with the owner not in
     * the dragon's (old) level, so the DragonInstance dimension update is skipped
     * while the inventory hand-off still fires; the next summon then reads the stale
     * dimension and null-clobbers the good inventory entry (#98's items-gone /
     * flags-survive signature). Since Wave 2 there are no hand-off blocks at all —
     * inventories live in a single global (overworld) store — and this scenario must
     * keep the items.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void chestInventorySurvivesTwoCrossDimensionSummons(ExtendedGameTestHelper helper) {
        // Went green in Wave 1: the B2 null-put fix (skip put(uuid, null) in both inventory
        // hand-off blocks) removed this scenario's clobber vector ahead of the Wave 2
        // hand-off consolidation (B1). Kept required as the B1 regression net.
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        DragonWhistleHandler.setDragon(player, dragon, 0);
        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == 0) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // Equip a chest with cargo while the dragon is in the overworld
        dragon.equipChest(new ItemStack(Items.CHEST), SoundSource.MASTER);
        dragon.getInventory().setItem(5, new ItemStack(Items.DIAMOND, 64));

        var server = helper.getLevel().getServer();
        var netherDim = server.getLevel(Level.NETHER);
        if (netherDim == null) {
            helper.fail("Nether level is not available");
            return;
        }

        // Dragon walks into the Nether (owner visible in the source level)
        var toNether = new DimensionTransition(
                netherDim, new Vec3(0, 100, 0), new Vec3(0, 0, 0), 0, 0, true, DimensionTransition.DO_NOTHING);
        var netherDragon = (TameableDragonEntity) dragon.changeDimension(toNether);
        if (netherDragon == null) {
            helper.fail("First dimension change returned null");
            return;
        }

        // Cross-dimension summons can complete asynchronously (Wave 2): gametest Nether
        // entities are parked in non-visible entity sections, so the summon path tickets
        // the dragon's chunk and re-checks over the following ticks. Run the two summons
        // as a sequence with a wait after each instead of asserting synchronously.
        helper.startSequence()
                .thenExecute(() -> {
                    // Summon #1: cross-dimension
                    if (!DragonWhistleHandler.callDragon(player)) {
                        helper.fail("First cross-dimension summon failed");
                    }
                })
                .thenWaitUntil(() -> {
                    var afterFirst = DragonWhistleHandler.findDragon(player, 0);
                    helper.assertTrue(afterFirst != null, "Dragon not found after first cross-dimension summon");
                    helper.assertTrue(
                            afterFirst.hasChest()
                                    && afterFirst
                                            .getInventory()
                                            .getItem(DragonInventory.CHEST_SLOT)
                                            .is(Items.CHEST)
                                    && !afterFirst.getInventory().getItem(5).isEmpty(),
                            "Dragon chest inventory lost after the FIRST cross-dimension summon");
                })
                .thenExecute(() -> {
                    // Dragon wanders back through the portal and returns (#123's exact repro
                    // shape): Overworld -> Nether (owner visible), then Nether -> Overworld
                    // (owner NOT in the Nether, so a level-scoped owner lookup would fail
                    // during the return transit).
                    var afterFirst = DragonWhistleHandler.findDragon(player, 0);
                    helper.assertTrue(afterFirst != null, "Dragon vanished between the two summons");

                    var wanderToNether = new DimensionTransition(
                            netherDim,
                            new Vec3(16, 100, 16),
                            new Vec3(0, 0, 0),
                            0,
                            0,
                            true,
                            DimensionTransition.DO_NOTHING);
                    var wanderer = (TameableDragonEntity) afterFirst.changeDimension(wanderToNether);
                    if (wanderer == null) {
                        helper.fail("Second dimension change returned null");
                        return;
                    }

                    var backToOverworld = new DimensionTransition(
                            helper.getLevel(),
                            new Vec3(player.getX(), player.getY(), player.getZ()),
                            new Vec3(0, 0, 0),
                            0,
                            0,
                            true,
                            DimensionTransition.DO_NOTHING);
                    var returned = (TameableDragonEntity) wanderer.changeDimension(backToOverworld);
                    if (returned == null) {
                        helper.fail("Return dimension change returned null");
                        return;
                    }

                    // Summon #2
                    if (!DragonWhistleHandler.callDragon(player)) {
                        helper.fail("Second cross-dimension summon failed");
                    }
                })
                .thenWaitUntil(() -> {
                    var afterSecond = DragonWhistleHandler.findDragon(player, 0);
                    helper.assertTrue(afterSecond != null, "Dragon not found after second cross-dimension summon");
                    helper.assertTrue(
                            afterSecond
                                            .getInventory()
                                            .getItem(DragonInventory.CHEST_SLOT)
                                            .is(Items.CHEST)
                                    && !afterSecond.getInventory().getItem(5).isEmpty(),
                            "Dragon chest inventory lost after two consecutive cross-dimension summons");
                })
                .thenSucceed();
    }

    /**
     * Tests CompleteDataSync's client routing decision: a payload carrying another
     * player's id must NOT be applied to the local player's capability.
     *
     * <p>
     * Baseline defect: CompleteDataSync.handle ignores the payload's playerId and
     * deserializes every received payload into the local player's handler — the
     * #88/#113 cross-player whistle-binding corruption vector. The routing decision is
     * extracted into the pure predicate shouldApplyToLocalPlayer (behavior-preserving:
     * it currently always returns true).
     *
     * @param helper The game test helper
     */
    @EmptyTemplate
    @GameTest
    @TestHolder
    public static void completeDataSyncDropsForeignPayloads(ExtendedGameTestHelper helper) {
        if (!CompleteDataSync.shouldApplyToLocalPlayer(42, 42)) {
            helper.fail("Payload addressed to the local player must be applied");
            return;
        }

        if (CompleteDataSync.shouldApplyToLocalPlayer(42, 99)) {
            helper.fail("Payload addressed to ANOTHER player (id 42) must not be applied to the"
                    + " local player (id 99) — #88/#113 corruption vector");
            return;
        }

        helper.succeed();
    }

    /**
     * Wave 5, T1 (Fix A1): a player with no whistle and no bound dragon at all must get
     * {@code false} back from {@code summonDragon} — not a silently-discarded failure.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void summonDragonReturnsFalseWithoutBoundDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        if (DragonWhistleHandler.summonDragon(player)) {
            helper.fail("summonDragon returned true for a player with no whistle/bound dragon at all");
            return;
        }

        helper.succeed();
    }

    /**
     * Wave 5, T1 (Fix A1): a player with a whistle bound to a valid, nearby dragon must
     * get {@code true} back from {@code summonDragon} — the return value must propagate
     * {@code callDragon}'s real result, not a hardcoded value.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void summonDragonReturnsTrueForValidNearbyDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);
        DragonWhistleHandler.setDragon(player, dragon, 0);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == 0) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        if (!DragonWhistleHandler.summonDragon(player)) {
            helper.fail("summonDragon returned false for a player with a valid nearby bound dragon");
            return;
        }

        helper.succeed();
    }

    /**
     * Wave 5, T2 (Fix B4): when two live dragons share one dragonUUID and EXACTLY ONE is
     * flagged {@code respawnedFromSnapshot}, the join-time dedup check must reclaim
     * (discard) ONLY the flagged clone and re-point the whistle binding at the proven
     * original — never the reverse.
     *
     * <p>
     * Setup mirrors the real race this defends against: {@code respawnDragonFromSnapshot}
     * mints a flagged clone and points the binding at it; the real (unflagged) original
     * then re-joins (simulated here via serialize/discard/reload, same technique as
     * {@link #unboundDragonSurvivesChunkReload}) — which is exactly when the join-time
     * dedup mismatch fires.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void snapshotCloneReclaimedWhenProven(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // The "original": tamed normally, never touched the snapshot-respawn path —
        // respawnedFromSnapshot stays false.
        var original = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        original.setBreed(DragonBreedsRegistry.getDefault());
        original.tamedFor(player, true);
        // Distinguishing marker (Wave 5 review fix #6 coverage): lets the assertion
        // below tell "NBT refreshed from the ORIGINAL" apart from "still the stale
        // clone-era snapshot written when the whistle was first bound to the clone".
        original.setCustomName(Component.literal("OriginalMarker"));

        // The "clone": a second dragon sharing the SAME dragonUUID, flagged the way the
        // real respawnDragonFromSnapshot path flags its mint (including a FRESH mint
        // timestamp — Wave 5 review Blocker 1(b): the reclaim only trusts the flag
        // within its evidence window).
        var clone = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS.offset(4, 0, 0));
        clone.setBreed(DragonBreedsRegistry.getDefault());
        clone.setDragonUUID(original.getDragonUUID());
        clone.setRespawnedFromSnapshot(true);
        clone.setSnapshotMintGameTime(helper.getLevel().getServer().overworld().getGameTime());

        // Bind the whistle at the CLONE — mirrors respawnDragonFromSnapshot re-pointing
        // the binding at the entity it just minted.
        DragonWhistleHandler.setDragon(player, clone, 0);

        var originalUuid = original.getUUID();
        var tag = new CompoundTag();
        if (!original.save(tag)) {
            helper.fail("Failed to serialize the original dragon");
            return;
        }
        original.discard();

        var reloaded = EntityType.loadEntityRecursive(tag, helper.getLevel(), entity -> entity);
        if (reloaded == null) {
            helper.fail("Failed to deserialize the original dragon");
            return;
        }

        if (!helper.getLevel().addFreshEntity(reloaded)) {
            helper.fail("Original dragon failed to (re)join the level");
            return;
        }

        // The join handler queues the clone's discard for the NEXT server tick rather
        // than discarding synchronously (CME/ghost-entity risk inside
        // EntityJoinLevelEvent) — run that queue directly instead of waiting on a real
        // tick.
        DragonWhistleHandler.processPendingReclaims(helper.getLevel().getServer());

        if (clone.isAlive()) {
            helper.fail("Flagged snapshot clone was not reclaimed (discarded) after the proven original re-joined");
            return;
        }

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        var boundUuid = cap.lastSummons.get(0);
        if (boundUuid == null || !boundUuid.equals(originalUuid)) {
            helper.fail("Whistle binding was not re-pointed at the original after the clone was reclaimed");
            return;
        }

        var found = DragonWhistleHandler.findDragon(player, 0);
        if (found == null || !found.getUUID().equals(originalUuid)) {
            helper.fail("Original dragon is not findable via the whistle binding after the reclaim");
            return;
        }

        // Wave 5 review fix #6: the NBT snapshot must be refreshed from the ORIGINAL,
        // not left as the clone-era snapshot written when the whistle was first bound
        // to the clone (which would resurrect clone stats on the dragon's next death).
        var refreshedNbt = cap.dragonNBTs.get(0);
        if (refreshedNbt == null || !refreshedNbt.contains("CustomName")) {
            helper.fail("dragonNBTs was not refreshed from the original after the reclaim (still the clone-era"
                    + " snapshot, or missing entirely)");
            return;
        }

        helper.succeed();
    }

    /**
     * Wave 5, T2 passenger-guard variant (Fix B4): a flagged clone currently carrying a
     * passenger must be left alone (log only) — both entities survive.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void snapshotCloneReclaimSkippedWithPassenger(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var original = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        original.setBreed(DragonBreedsRegistry.getDefault());
        original.tamedFor(player, true);

        var clone = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS.offset(4, 0, 0));
        clone.setBreed(DragonBreedsRegistry.getDefault());
        clone.setDragonUUID(original.getDragonUUID());
        clone.setRespawnedFromSnapshot(true);
        clone.setSnapshotMintGameTime(helper.getLevel().getServer().overworld().getGameTime());

        // Give the clone a passenger — the reclaim must refuse to touch it.
        var passenger = helper.spawn(EntityType.CHICKEN, DMRTestConstants.TEST_POS.offset(4, 0, 0));
        if (!passenger.startRiding(clone, true)) {
            helper.fail("Test setup failed: passenger could not mount the clone");
            return;
        }

        DragonWhistleHandler.setDragon(player, clone, 0);
        var cloneUuid = clone.getUUID();

        var tag = new CompoundTag();
        if (!original.save(tag)) {
            helper.fail("Failed to serialize the original dragon");
            return;
        }
        original.discard();

        var reloaded = EntityType.loadEntityRecursive(tag, helper.getLevel(), entity -> entity);
        if (reloaded == null) {
            helper.fail("Failed to deserialize the original dragon");
            return;
        }

        if (!helper.getLevel().addFreshEntity(reloaded)) {
            helper.fail("Original dragon failed to (re)join the level");
            return;
        }

        DragonWhistleHandler.processPendingReclaims(helper.getLevel().getServer());

        if (!clone.isAlive()) {
            helper.fail("Passenger-carrying snapshot clone was reclaimed (discarded) despite having a passenger");
            return;
        }

        if (!reloaded.isAlive()) {
            helper.fail("Original dragon did not survive the passenger-guard scenario");
            return;
        }

        // Regression guard (Wave 5 review, test iii): a skipped reclaim must leave the
        // binding exactly as it was — still pointing at the clone. Silently re-pointing
        // it anyway (while leaving the clone alive) would orphan the clone from its
        // owner's whistle without actually resolving anything.
        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        var boundUuid = cap.lastSummons.get(0);
        if (boundUuid == null || !boundUuid.equals(cloneUuid)) {
            helper.fail("Whistle binding was changed even though the passenger-guard skipped the reclaim");
            return;
        }

        helper.succeed();
    }

    /**
     * Wave 5 review, test (ii): a {@code respawnedFromSnapshot} flag older than {@link
     * DragonWhistleHandler#SNAPSHOT_CLONE_EVIDENCE_WINDOW_TICKS} must NOT be trusted as
     * clone-proof — both entities survive, left to {@code duplicate_resolution=LOG}'s
     * plain logging instead of an automatic reclaim.
     *
     * <p>
     * This is the reverse-provenance safety net: a "clone" that has survived
     * unchallenged long enough to be well outside the evidence window has almost
     * certainly accrued its own legitimate progression (taming, playtime, inventory),
     * and discarding it on a stale flag would itself be exactly the kind of silent
     * duplication-cleanup bug this fork exists to eliminate.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void snapshotCloneReclaimSkippedWhenEvidenceStale(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var original = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        original.setBreed(DragonBreedsRegistry.getDefault());
        original.tamedFor(player, true);

        var clone = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS.offset(4, 0, 0));
        clone.setBreed(DragonBreedsRegistry.getDefault());
        clone.setDragonUUID(original.getDragonUUID());
        clone.setRespawnedFromSnapshot(true);
        // Far enough in the past that "current game time - this" always exceeds the
        // evidence window, regardless of how far along the gametest world's clock is.
        clone.setSnapshotMintGameTime(-(DragonWhistleHandler.SNAPSHOT_CLONE_EVIDENCE_WINDOW_TICKS + 1_000_000L));

        DragonWhistleHandler.setDragon(player, clone, 0);
        var cloneUuid = clone.getUUID();

        var tag = new CompoundTag();
        if (!original.save(tag)) {
            helper.fail("Failed to serialize the original dragon");
            return;
        }
        original.discard();

        var reloaded = EntityType.loadEntityRecursive(tag, helper.getLevel(), entity -> entity);
        if (reloaded == null) {
            helper.fail("Failed to deserialize the original dragon");
            return;
        }

        if (!helper.getLevel().addFreshEntity(reloaded)) {
            helper.fail("Original dragon failed to (re)join the level");
            return;
        }

        DragonWhistleHandler.processPendingReclaims(helper.getLevel().getServer());

        if (!clone.isAlive()) {
            helper.fail("Snapshot clone with a stale (out-of-window) mint timestamp was reclaimed anyway");
            return;
        }

        if (!reloaded.isAlive()) {
            helper.fail("Original dragon did not survive the stale-evidence scenario");
            return;
        }

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        var boundUuid = cap.lastSummons.get(0);
        if (boundUuid == null || !boundUuid.equals(cloneUuid)) {
            helper.fail("Whistle binding was changed even though the evidence-window check skipped the reclaim");
            return;
        }

        helper.succeed();
    }

    /**
     * Wave 5 review, test (i) (Blocker 1(a)): a dragon respawned after a CONFIRMED
     * death (a recorded {@code respawnDelays} entry for its whistle slot — the normal
     * death -> respawn-delay -> whistle-call flow) must NOT be flagged
     * {@code respawnedFromSnapshot}. Vanilla death already removed the original entity,
     * so this mint can never be a clone; flagging it anyway made every dragon that ever
     * died and was re-summoned permanently eligible for the join-time reclaim to
     * discard, including a player's live, legitimate dragon.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void deathRespawnMintIsNotFlagged(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);
        DragonWhistleHandler.setDragon(player, dragon, 0);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        // getDragonSummonIndex(Player) resolves the summon slot from the WHISTLE ITEM
        // in hand, not from dragonInstances directly — without one, canCall refuses
        // with no_whistle before ever reaching the respawn path.
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == 0) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // Simulate the END STATE of the real death -> respawn-delay -> whistle-call
        // flow (DragonWhistleEvent#onEntityDeath populates respawnDelays; the
        // onLivingUpdate countdown reaching zero leaves the entry in place at 0 — see
        // canCall's respawnDelays > 0 gate, which is why callDragon can even reach this
        // point) without waiting out a real death animation + respawn timer.
        cap.respawnDelays.put(0, 0);
        dragon.discard();

        if (!DragonWhistleHandler.summonDragon(player)) {
            helper.fail("summonDragon returned false immediately — expected the ticket-and-defer path to accept"
                    + " (chunk is already loaded in a gametest, so the deferred re-check should find nothing and"
                    + " fall through to the snapshot respawn once its deadline passes)");
            return;
        }

        helper.succeedWhen(() -> {
            var respawned = DragonWhistleHandler.findDragon(player, 0);
            helper.assertTrue(
                    respawned != null,
                    "Dragon was not respawned from snapshot after the deferred"
                            + " summon's deadline passed following a confirmed death");
            helper.assertTrue(
                    !respawned.isRespawnedFromSnapshot(),
                    "Wave 5 review Blocker 1(a): a death-respawn mint must NOT be flagged as a snapshot clone");
        });
    }

    /**
     * Wave 5 verify round (Blocker, traced route (a)): with {@code allow_respawn=true}
     * and {@code respawn_time=0} (a valid, minimum config value), {@code
     * DragonWhistleEvent#onEntityDeath}'s online-owner branch used to match NEITHER its
     * {@code !allow_respawn} arm NOR its old {@code respawn_time > 0} arm, so no {@code
     * respawnDelays} entry was ever recorded for the death — the exact signal
     * {@code isConfirmedDead} relies on. Triggers a REAL death (through the actual
     * event handler, not a simulated end-state) to prove the handler itself now records
     * the entry, then confirms the eventual respawn mint stays unflagged.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void deathRespawnMintIsNotFlaggedWithZeroRespawnTime(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);
        DragonWhistleHandler.setDragon(player, dragon, 0);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == 0) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // Narrow the mutable-global-config window to just the death trigger itself.
        var previousAllowRespawn = ServerConfig.ALLOW_RESPAWN;
        var previousRespawnTime = ServerConfig.RESPAWN_TIME;
        ServerConfig.ALLOW_RESPAWN = true;
        ServerConfig.RESPAWN_TIME = 0;
        try {
            dragon.kill();
        } finally {
            ServerConfig.ALLOW_RESPAWN = previousAllowRespawn;
            ServerConfig.RESPAWN_TIME = previousRespawnTime;
        }

        if (!cap.respawnDelays.containsKey(0)) {
            helper.fail("onEntityDeath did not record a respawnDelays entry for a respawn_time=0 death — the"
                    + " traced coverage gap");
            return;
        }

        if (!DragonWhistleHandler.summonDragon(player)) {
            helper.fail("summonDragon returned false immediately after a respawn_time=0 death");
            return;
        }

        helper.succeedWhen(() -> {
            var respawned = DragonWhistleHandler.findDragon(player, 0);
            helper.assertTrue(respawned != null, "Dragon was not respawned after a respawn_time=0 death");
            helper.assertTrue(
                    !respawned.isRespawnedFromSnapshot(),
                    "Wave 5 verify round: a respawn_time=0 death-respawn mint must NOT be flagged as a snapshot"
                            + " clone");
        });
    }

    /**
     * Wave 5 verify round (Blocker, traced route (b)): a dragon that dies in a
     * dimension its owner is NOT currently in never populates {@code respawnDelays} —
     * {@code TamableAnimal#getOwner()}'s level-scoped lookup can't see the owner, so
     * {@code onEntityDeath} takes the offline/world-data branch and writes only a
     * per-level {@code DragonWorldData} dead-dragon record. That record used to only
     * ever get copied into {@code respawnDelays} when the owner's own {@code
     * EntityJoinLevelEvent} next fired for THAT dimension; a summon attempted before
     * that join (as here — the mock player never leaves the overworld) saw neither the
     * entity nor a {@code respawnDelays} entry, so it minted flagged.
     * {@code isConfirmedDead} now also sweeps world data directly, closing the gap.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void deathRespawnMintIsNotFlaggedAcrossDimensions(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);
        DragonWhistleHandler.setDragon(player, dragon, 0);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == 0) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        var server = helper.getLevel().getServer();
        var netherDim = server.getLevel(Level.NETHER);
        if (netherDim == null) {
            helper.fail("Nether level is not available");
            return;
        }

        // Send the dragon to the Nether WITHOUT the owner (the mock player stays in the
        // overworld gametest structure) — the exact shape of a wandered-off/ridden-away
        // dragon dying somewhere its owner isn't.
        var toNether = new DimensionTransition(
                netherDim, new Vec3(0, 100, 0), new Vec3(0, 0, 0), 0, 0, true, DimensionTransition.DO_NOTHING);
        var netherDragon = (TameableDragonEntity) dragon.changeDimension(toNether);
        if (netherDragon == null) {
            helper.fail("Dimension change to the Nether returned null");
            return;
        }

        netherDragon.kill();

        if (cap.respawnDelays.containsKey(0)) {
            helper.fail("respawnDelays was populated by a cross-dimension death without the owner ever"
                    + " (re)joining that dimension — test setup invalid, this should be impossible");
            return;
        }

        if (!DragonWhistleHandler.summonDragon(player)) {
            helper.fail("summonDragon returned false immediately after a cross-dimension death");
            return;
        }

        helper.succeedWhen(() -> {
            var respawned = DragonWhistleHandler.findDragon(player, 0);
            helper.assertTrue(respawned != null, "Dragon was not respawned after a cross-dimension death");
            helper.assertTrue(
                    !respawned.isRespawnedFromSnapshot(),
                    "Wave 5 verify round: a cross-dimension death-respawn mint must NOT be flagged as a snapshot"
                            + " clone (respawnDelays never populated; isConfirmedDead must fall back to the"
                            + " world-data record)");
        });
    }
}
