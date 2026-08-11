package dmr.tests;

import dmr.DMRTestConstants;
import dmr.DragonMounts.common.capability.DragonOwnerCapability;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler.DragonInstance;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.registry.DragonBreedsRegistry;
import dmr.DragonMounts.registry.ModCapabilities;
import dmr.DragonMounts.registry.ModEntities;
import dmr.DragonMounts.registry.ModItems;
import dmr.DragonMounts.server.commands.DMRCommand;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.items.DragonWhistleItem;
import dmr.DragonMounts.server.worlddata.DragonWorldDataManager;
import dmr.DragonMounts.util.PlayerStateUtils;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

@PrefixGameTestTemplate(false)
@ForEachTest(groups = "Dragon Whistles")
public class DragonWhistleTests {

    /**
     * Tests the DragonWhistleHandler.getDragonWhistleItem method.
     *
     * <p>
     * This test verifies that: 1. When a player has no dragon whistle, the method
     * returns null 2. When a player has a dragon whistle in their main hand and the
     * corresponding dragon data, the method correctly returns the whistle item
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void findWhistleItem(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Test with no whistle
        var whistleItem = DragonWhistleHandler.getDragonWhistleItem(player);
        if (whistleItem != null) {
            helper.fail("Found whistle when none should exist");
        }

        // Test with whistle in main hand
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));

            // Setup player capability to recognize this whistle
            var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
            cap.setPlayerInstance(player);
            var handler = PlayerStateUtils.getHandler(player);
            handler.dragonNBTs.put(
                    ((DragonWhistleItem) whistle.get()).getColor().getId(), new CompoundTag());
            handler.dragonInstances.put(
                    ((DragonWhistleItem) whistle.get()).getColor().getId(),
                    new DragonInstance(player.level, UUID.randomUUID(), UUID.randomUUID()));

            whistleItem = DragonWhistleHandler.getDragonWhistleItem(player);
            if (whistleItem == null) {
                helper.fail("Could not find whistle in main hand");
            }
        }

        helper.succeed();
    }

    /**
     * Tests the DragonWhistleHandler.getDragonSummonIndex method.
     *
     * <p>
     * This test verifies that: 1. When a player has no dragon whistle, the method
     * returns -1 2. When a player has a dragon whistle in their main hand and the
     * corresponding dragon data, the method correctly returns the whistle's color
     * ID
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void getWhistleIndex(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Test with no whistle
        var index = DragonWhistleHandler.getDragonSummonIndex(player);
        if (index != -1) {
            helper.fail("Found whistle index when none should exist");
        }

        // Test with whistle in main hand
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));

            // Setup player capability to recognize this whistle
            player.getData(ModCapabilities.PLAYER_CAPABILITY).setPlayerInstance(player);
            var handler = PlayerStateUtils.getHandler(player);
            int whistleId = ((DragonWhistleItem) whistle.get()).getColor().getId();
            handler.dragonNBTs.put(whistleId, new CompoundTag());
            handler.dragonInstances.put(
                    whistleId, new DragonInstance(player.level, UUID.randomUUID(), UUID.randomUUID()));

            index = DragonWhistleHandler.getDragonSummonIndex(player);
            if (index != whistleId) {
                helper.fail("Incorrect whistle index: expected " + whistleId + " but got " + index);
            }
        }

        helper.succeed();
    }

    /**
     * Tests the DragonWhistleHandler.setDragon method.
     *
     * <p>
     * This test verifies that: 1. When a dragon is set to a whistle index, the
     * dragon instance is properly stored in the player's capability 2. The stored
     * dragon instance has the correct UUID matching the original dragon
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void bindDragonToWhistle(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        // Set dragon to whistle
        DragonWhistleHandler.setDragon(player, dragon, 1);

        // Verify dragon is set
        if (!player.getData(ModCapabilities.PLAYER_CAPABILITY).dragonInstances.containsKey(1)) {
            helper.fail("Dragon instance not found in player capability");
        }

        var instance = player.getData(ModCapabilities.PLAYER_CAPABILITY)
                .dragonInstances
                .get(1);
        if (instance == null) {
            helper.fail("Dragon instance is null");
        }

        if (!instance.getUUID().equals(dragon.getDragonUUID())) {
            helper.fail("Dragon UUID mismatch");
        }

        helper.succeed();
    }

    /**
     * Tests the DragonWhistleHandler.canCall method when a player has no whistle.
     *
     * <p>
     * This test verifies that: 1. When a player has no dragon whistle (index = -1),
     * the canCall method returns false
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void cannotCallWithoutWhistle(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Test with no whistle
        boolean canCall = DragonWhistleHandler.canCall(player, -1);
        if (canCall) {
            helper.fail("Player can call dragon with no whistle");
        }

        helper.succeed();
    }

    /**
     * Tests the DragonWhistleHandler.canCall method when a player has a whistle but
     * no dragon.
     *
     * <p>
     * This test verifies that: 1. When a player has a dragon whistle but no
     * associated dragon, the canCall method returns false
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void cannotCallWithoutDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Test with whistle but no dragon
        boolean canCall = DragonWhistleHandler.canCall(player, 1);
        if (canCall) {
            helper.fail("Player can call dragon when no dragon exists");
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-5 regression: proves the corrupted-binding repair contract this
     * commit's join-path {@code DragonNBTSync} null-guard defers to instead of
     * papering over. When {@code dragonInstances[index]} is present but
     * {@code dragonNBTs[index]} is absent for a player — exactly the divergence the
     * pre-fix join-path send used to mask with a silent empty-tag "delete" packet,
     * which a real client's {@code KeyInputHandler} interprets as gating the summon
     * keybind off with NO feedback — {@code DragonWhistleHandler#canCall} must
     * detect the corruption, wipe all three whistle-state maps for that index, and
     * return {@code false}.
     *
     * <p>
     * This gametest runs entirely server-side (as {@code canCall}'s own corruption
     * check does — see its {@code !player.level.isClientSide} guard) and therefore
     * cannot reproduce the CLIENT-side keybind gate the original bug actually lived
     * in; there is no client in this dedicated-server-only harness. It instead pins
     * the repair contract the fix's commit body cross-references: once a request
     * reaches the server (which it now can, since the join path no longer silently
     * wipes the client's cached snapshot), canCall repairs the corruption cleanly
     * rather than leaving it to recur.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void corruptedBindingIsRepairedByCanCallRatherThanSilentlyWiped(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = PlayerStateUtils.getHandler(player);
        // Manufacture the exact divergence W8-SYNC-5 targets: setDragon/
        // setDragonToWhistle populates BOTH dragonInstances and dragonNBTs for this
        // index — remove only the NBT half.
        cap.dragonNBTs.remove(index);

        if (!cap.dragonInstances.containsKey(index) || cap.dragonNBTs.containsKey(index)) {
            helper.fail("Setup failed: did not manufacture a dragonInstances-without-dragonNBTs divergence");
            return;
        }

        boolean canCall = DragonWhistleHandler.canCall(player, index);

        if (canCall) {
            helper.fail("canCall returned true for a corrupted (dragonInstances-without-dragonNBTs) binding");
        }

        if (cap.dragonInstances.containsKey(index)
                || cap.dragonNBTs.containsKey(index)
                || cap.respawnDelays.containsKey(index)) {
            helper.fail("canCall did not clear the corrupted binding's whistle-state maps for index " + index);
        }

        helper.succeed();
    }

    /**
     * Tests the DragonWhistleHandler.findDragon method.
     *
     * <p>
     * This test verifies that: 1. When a dragon is set to a whistle index, the
     * findDragon method can locate the dragon 2. The found dragon has the correct
     * UUID matching the original dragon
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void locateBoundDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        // Set dragon to whistle
        DragonWhistleHandler.setDragon(player, dragon, 1);

        // Find dragon
        var foundDragon = DragonWhistleHandler.findDragon(player, 1);
        if (foundDragon == null) {
            helper.fail("Could not find dragon");
        }

        if (!foundDragon.getDragonUUID().equals(dragon.getDragonUUID())) {
            helper.fail("Found wrong dragon");
        }

        helper.succeed();
    }

    /**
     * Tests the DragonWhistleHandler.callDragon method.
     *
     * <p>
     * This test verifies that: 1. When a player has a dragon whistle and an
     * associated dragon, the callDragon method returns true 2. The dragon can be
     * successfully called using the whistle
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate
    @GameTest
    @TestHolder
    public static void callBoundDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        // Set dragon to whistle
        DragonWhistleHandler.setDragon(player, dragon, 1);

        // Setup player capability to recognize this whistle
        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);
        var handler = PlayerStateUtils.getHandler(player);

        // Add a whistle to player's hand
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            int whistleId = ((DragonWhistleItem) whistle.get()).getColor().getId();
            if (whistleId == 1) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                handler.dragonInstances.put(
                        whistleId, new DragonInstance(player.level, dragon.getUUID(), dragon.getDragonUUID()));
                handler.dragonNBTs.put(whistleId, new CompoundTag());
                break;
            }
        }

        // Call dragon
        boolean result = DragonWhistleHandler.callDragon(player);
        if (!result) {
            helper.fail("Failed to call dragon");
        }

        helper.succeed();
    }

    /**
     * Tests the DragonWhistleHandler.summonDragon method.
     *
     * <p>
     * This test verifies that: 1. When a player has a dragon whistle and an
     * associated dragon, the summonDragon method updates the lastCall timestamp 2.
     * The lastCall timestamp is different after summoning the dragon
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate
    @GameTest
    @TestHolder
    public static void summonUpdatesLastCall(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        // Set dragon to whistle
        DragonWhistleHandler.setDragon(player, dragon, 1);

        // Setup player capability to recognize this whistle
        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);
        var handler = PlayerStateUtils.getHandler(player);

        // Add a whistle to player's hand
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            int whistleId = ((DragonWhistleItem) whistle.get()).getColor().getId();

            if (whistleId == 1) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                handler.dragonInstances.put(
                        whistleId, new DragonInstance(player.level, dragon.getUUID(), dragon.getDragonUUID()));
                handler.dragonNBTs.put(whistleId, new CompoundTag());
                break;
            }
        }

        // Record the last call time before summoning
        Long lastCallBefore = handler.lastCall;

        // Summon dragon
        DragonWhistleHandler.summonDragon(player);

        // Verify that lastCall was updated
        if (Objects.equals(handler.lastCall, lastCallBefore)) {
            helper.fail("Last call was not updated");
        }

        helper.succeed();
    }

    /**
     * Tests that a dragon follows the player when called with a whistle.
     *
     * <p>
     * This test verifies that:
     * 1. A dragon can be tamed and bound to a whistle
     * 2. When called with a whistle, the dragon will follow the player
     * 3. The dragon will teleport to the player if it's too far away
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void dragonFollowsWhenCalled(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Spawn and tame a dragon
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        // Set dragon to whistle
        DragonWhistleHandler.setDragon(player, dragon, 1);

        // Setup player capability to recognize this whistle
        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);
        var handler = PlayerStateUtils.getHandler(player);

        // Add a whistle to player's hand
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            int whistleId = ((DragonWhistleItem) whistle.get()).getColor().getId();

            if (whistleId == 1) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // Move dragon far away
        dragon.setPos(dragon.getX() + 5, dragon.getY(), dragon.getZ() + 5);

        // Call the dragon
        boolean result = DragonWhistleHandler.callDragon(player);
        if (!result) {
            helper.fail("Failed to call dragon");
        }

        dragon = DragonWhistleHandler.findDragon(player, 1);

        // Tick entities to allow the dragon to respond
        for (int i = 0; i < 20; i++) {
            player.tick();
            dragon.tick();
        }

        // Verify the dragon is now close to the player
        if (dragon.position().distanceTo(player.position()) > 10) {
            helper.fail("Dragon did not teleport to player when called");
        }

        helper.succeed();
    }

    /**
     * Tests that multiple dragons can be called with different whistles.
     *
     * <p>
     * This test verifies that:
     * 1. Multiple dragons can be tamed and bound to different whistles
     * 2. Each dragon can be called using its specific whistle
     * 3. The correct dragon responds to each whistle
     *
     * @param helper The game test helper
     */
    // TODO Fix this test
    // @EmptyTemplate(floor = true)
    // @GameTest
    // @TestHolder
    public static void callMultipleDragons(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Spawn and tame multiple dragons
        var dragon1 = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        var dragon2 = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS.offset(2, 0, 0));

        dragon1.setBreed(DragonBreedsRegistry.getDefault());
        dragon2.setBreed(DragonBreedsRegistry.getDefault());

        dragon1.tamedFor(player, true);
        dragon2.tamedFor(player, true);

        // Setup player capability
        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        // Set dragons to different whistles
        DragonWhistleHandler.setDragon(player, dragon1, 0);
        DragonWhistleHandler.setDragon(player, dragon2, 1);

        // Move dragons far away
        dragon1.setPos(dragon1.getX() + 50, dragon1.getY(), dragon1.getZ() + 50);
        dragon2.setPos(dragon2.getX() - 50, dragon2.getY(), dragon2.getZ() - 50);

        dragon1.setOrderedToSit(true);
        dragon2.setOrderedToSit(true);

        // Disable ai to prevent dragons from moving when not meant to
        dragon1.setNoAi(true);
        dragon2.setNoAi(true);

        // Make sure falling doesnt impact the test result
        dragon1.setNoGravity(true);
        dragon2.setNoGravity(true);

        // Store original positions
        var dragon1OrigPos = dragon1.position();
        var dragon2OrigPos = dragon2.position();

        // Add whistle 0 to player's hand
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            int whistleId = ((DragonWhistleItem) whistle.get()).getColor().getId();

            if (whistleId == 0) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // Call dragon1 using whistle 0
        boolean callResult = DragonWhistleHandler.callDragon(player);

        if (!callResult) {
            helper.fail("Failed to call dragon1 with whistle 0");
        }

        dragon1 = DragonWhistleHandler.findDragon(player, 0);

        // Tick entities to allow the dragon to respond
        for (int i = 0; i < 20; i++) {
            player.tick();
            dragon1.tick();
            dragon2.tick();
        }

        // Verify dragon1 moved toward the player
        if (dragon1.position().distanceTo(player.position()) > 10) {
            helper.fail("Dragon1 did not teleport to player when called with whistle 0");
        }

        // Verify dragon2 stayed in place
        if (dragon2.position().distanceTo(dragon2OrigPos) > 5) {
            helper.fail("Dragon2 moved when whistle 0 was used");
        }

        // Move dragons far away again
        dragon1.setPos(dragon1.getX() + 50, dragon1.getY(), dragon1.getZ() + 50);
        dragon2.setPos(dragon2.getX() - 50, dragon2.getY(), dragon2.getZ() - 50);

        // Update original positions
        dragon1OrigPos = dragon1.position();

        // Add whistle 1 to player's hand
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            int whistleId = ((DragonWhistleItem) whistle.get()).getColor().getId();

            if (whistleId == 1) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // Call dragon2 using whistle 1
        callResult = DragonWhistleHandler.callDragon(player);

        if (!callResult) {
            helper.fail("Failed to call dragon2 with whistle 1");
        }

        dragon2 = DragonWhistleHandler.findDragon(player, 1);

        // Tick entities to allow the dragon to respond
        for (int i = 0; i < 20; i++) {
            player.tick();
            dragon1.tick();
            dragon2.tick();
        }

        // Verify dragon2 moved toward the player
        if (dragon2.position().distanceTo(player.position()) > 10) {
            helper.fail("Dragon2 did not teleport to player when called with whistle 1");
        }

        // Verify dragon1 stayed in place
        if (dragon1.position().distanceTo(dragon1OrigPos) > 5) {
            helper.fail("Dragon1 moved when whistle 1 was used");
        }

        helper.succeed();
    }

    /**
     * Tests that a dragon can be called across dimensions.
     *
     * <p>
     * This test verifies that:
     * 1. A dragon can be tamed and bound to a whistle
     * 2. When the dragon is in a different dimension, calling it TELEPORTS the same
     * entity (identical entity UUID) instead of cloning it from the NBT snapshot
     * 3. Exactly one dragon with the bound dragonUUID exists across ALL server levels
     * (no orphan left behind in the source dimension)
     * 4. The dragon arrives in the summoning player's dimension
     * 5. The dragon's inventory is transferred correctly between dimensions
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void callAcrossDimensions(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Spawn and tame a dragon
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        // Entity UUID survives changeDimension (restoreFrom copies it), so this is the
        // identity the summoned dragon must still carry if it was teleported, not cloned.
        var entityUuid = dragon.getUUID();
        var dragonUuid = dragon.getDragonUUID();

        // Set dragon to whistle
        DragonWhistleHandler.setDragon(player, dragon, 0);

        // Setup player capability
        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        // Add a whistle to player's hand
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            int whistleId = ((DragonWhistleItem) whistle.get()).getColor().getId();

            if (whistleId == 0) {
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

        var dimensionTransition = new DimensionTransition(
                netherDim, new Vec3(0, 0, 0), new Vec3(0, 0, 0), 0, 0, true, DimensionTransition.DO_NOTHING);

        var netherDragon = (TameableDragonEntity) dragon.changeDimension(dimensionTransition);
        if (netherDragon == null) {
            helper.fail("changeDimension returned null when moving the dragon to the Nether");
            return;
        }

        // Give the dragon a chest with items
        netherDragon.equipChest(new ItemStack(Blocks.CHEST), SoundSource.MASTER);
        netherDragon.getInventory().setItem(0, new ItemStack(ModItems.DRAGON_ARMOR.get(), 1));

        // Call the dragon from the other dimension
        boolean callResult = DragonWhistleHandler.callDragon(player);

        // Verify the call was successful
        if (!callResult) {
            helper.fail("Failed to call dragon from another dimension");
            return;
        }

        // The summon can complete asynchronously: gametest Nether entities are parked in
        // non-visible entity sections, so the summon path tickets the dragon's chunk and
        // re-checks over the following ticks (Wave 2). Poll until the REAL dragon has
        // arrived instead of asserting synchronously.
        helper.succeedWhen(() -> {
            var checkDragon = DragonWhistleHandler.findDragon(player, 0);

            helper.assertTrue(checkDragon != null, "Dragon was not found after being called from another dimension");

            // Identity: a cross-dimension summon must move the SAME entity, not clone it
            helper.assertTrue(
                    checkDragon.getUUID().equals(entityUuid),
                    "Dragon was cloned instead of teleported: entity UUID changed from " + entityUuid + " to "
                            + checkDragon.getUUID());

            // Uniqueness: exactly one dragon with this dragonUUID may exist across all levels
            int count = 0;
            for (var serverLevel : server.getAllLevels()) {
                count += serverLevel
                        .getEntities(
                                ModEntities.DRAGON_ENTITY.get(), entity -> dragonUuid.equals(entity.getDragonUUID()))
                        .size();
            }
            helper.assertTrue(
                    count == 1,
                    "Expected exactly one dragon with dragonUUID " + dragonUuid + " across all levels, found " + count);

            // The dragon must have arrived in the player's dimension
            helper.assertTrue(
                    checkDragon.level().dimension().equals(helper.getLevel().dimension()),
                    "Dragon is not in the summoning player's dimension: "
                            + checkDragon.level().dimension().location());

            // Verify the dragon's inventory was transferred
            helper.assertTrue(
                    checkDragon.hasChest()
                            && !checkDragon.getInventory().getItem(0).isEmpty(),
                    "Dragon's inventory was not transferred correctly between dimensions");
        });
    }

    /**
     * Tests that tamed dragons don't despawn when far away.
     *
     * <p>
     * This test verifies that:
     * 1. Multiple dragons can be tamed by a player
     * 2. Tamed dragons don't despawn when far away
     * 3. The removeWhenFarAway method returns false for tamed dragons
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void tamedDragonsDoNotDespawn(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Spawn and tame multiple dragons
        var dragon1 = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        var dragon2 = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS.offset(2, 0, 0));
        var dragon3 = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS.offset(0, 0, 2));

        dragon1.setBreed(DragonBreedsRegistry.getDefault());
        dragon2.setBreed(DragonBreedsRegistry.getDefault());
        dragon3.setBreed(DragonBreedsRegistry.getDefault());

        // Tame all dragons
        dragon1.tamedFor(player, true);
        dragon2.tamedFor(player, true);
        dragon3.tamedFor(player, true);

        // Verify all dragons are tamed
        if (!dragon1.isTame() || !dragon2.isTame() || !dragon3.isTame()) {
            helper.fail("Not all dragons were tamed");
        }

        // Move dragons far away to test despawning
        dragon1.setPos(dragon1.getX() + 100, dragon1.getY(), dragon1.getZ() + 100);
        dragon2.setPos(dragon2.getX() + 100, dragon2.getY(), dragon2.getZ() - 100);
        dragon3.setPos(dragon3.getX() - 100, dragon3.getY(), dragon3.getZ() - 100);

        // Tick entities to allow potential despawning
        for (int i = 0; i < 100; i++) {
            player.tick();
            dragon1.tick();
            dragon2.tick();
            dragon3.tick();
        }

        // Verify dragons didn't despawn
        if (!dragon1.isAlive()) {
            helper.fail("Dragon1 despawned despite being tamed");
        }

        if (!dragon2.isAlive()) {
            helper.fail("Dragon2 despawned despite being tamed");
        }

        if (!dragon3.isAlive()) {
            helper.fail("Dragon3 despawned despite being tamed");
        }

        // Verify removeWhenFarAway returns false for tamed dragons
        if (dragon1.removeWhenFarAway(100) || dragon2.removeWhenFarAway(100) || dragon3.removeWhenFarAway(100)) {
            helper.fail("removeWhenFarAway returned true for tamed dragons");
        }

        helper.succeed();
    }

    /**
     * Tests that multiple dragons can be bound to different whistles.
     *
     * <p>
     * This test verifies that:
     * 1. Multiple dragons can be bound to different whistles
     * 2. Each dragon is correctly bound to its assigned whistle
     * 3. The correct whistle indices are assigned to each dragon
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void bindMultipleDragons(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        // Spawn and tame multiple dragons
        var dragon1 = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        var dragon2 = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS.offset(2, 0, 0));

        dragon1.setBreed(DragonBreedsRegistry.getDefault());
        dragon2.setBreed(DragonBreedsRegistry.getDefault());

        // Tame all dragons
        dragon1.tamedFor(player, true);
        dragon2.tamedFor(player, true);

        // Verify all dragons are tamed
        if (!dragon1.isTame() || !dragon2.isTame()) {
            helper.fail("Not all dragons were tamed");
        }

        // Bind dragons to whistles
        var handler = PlayerStateUtils.getHandler(player);

        // Bind dragon1 to whistle index 0
        PlayerStateUtils.getHandler(player).setDragonToWhistle(dragon1, 0);

        // Verify dragon1 is bound to whistle index 0
        if (!PlayerStateUtils.getHandler(player).isBoundToWhistle(dragon1)) {
            helper.fail("Dragon1 was not bound to whistle");
        }

        // Bind dragon2 to whistle index 1
        PlayerStateUtils.getHandler(player).setDragonToWhistle(dragon2, 1);

        // Verify dragon2 is bound to whistle index 1
        if (!PlayerStateUtils.getHandler(player).isBoundToWhistle(dragon2)) {
            helper.fail("Dragon2 was not bound to whistle");
        }

        // Verify correct whistle indices (empty OptionalInt = not bound at all)
        var index1 = DragonWhistleHandler.getDragonSummonIndex(player, dragon1.getDragonUUID());
        var index2 = DragonWhistleHandler.getDragonSummonIndex(player, dragon2.getDragonUUID());

        if (index1.isEmpty() || index1.getAsInt() != 0) {
            helper.fail("Dragon1 was bound to wrong whistle index: " + index1);
        }

        if (index2.isEmpty() || index2.getAsInt() != 1) {
            helper.fail("Dragon2 was bound to wrong whistle index: " + index2);
        }

        helper.succeed();
    }

    /**
     * Reserved for the same-dimension teleport-branch reach test below: default
     * {@code @EmptyTemplate} footprints (3x3x3, or this file's usual {@code floor =
     * true} platform) are far too small for an 80-block separation, and a raw {@code
     * setPos} into terrain OUTSIDE any loaded/ticketed structure would make {@code
     * findDragon}'s exact-entity-id lookup depend on incidental chunk-load timing
     * (entity-section visibility) instead of the summon logic under test — the exact
     * footprint-vs-chunk-visibility trap {@code PathNavigationTests}' own {@code
     * LARGE_TEMPLATE} javadoc documents and resolves the same way. Both the player and
     * the displaced dragon stay inside this single, fully-generated/loaded structure
     * for the whole test.
     */
    static final String LARGE_TEMPLATE = "160x24x160";

    /**
     * W8-SUMMON-2 regression: {@code summonExistingDragon}'s same-dimension teleport
     * branch now refreshes the whistle-binding {@link DragonInstance} (dimension,
     * entityId, dragonUUID, lastPos) from the dragon's FINAL post-teleport position,
     * rather than leaving it pointing at wherever it was captured at BIND time until
     * {@code TameableDragonEntity}'s own periodic 100-tick baseTick refresh catches
     * up.
     *
     * <p>
     * The player deliberately moves AFTER the dragon is bound and BEFORE it is
     * summoned: the teleport branch always lands the dragon exactly on the player's
     * CURRENT position, so if bind time and summon time shared the same player
     * position, a stale (unrefreshed) binding would coincidentally still equal
     * {@code dragon.blockPosition()} post-summon and this test would pass on both
     * pre- and post-fix code — the mid-flight player move is what makes bind-time
     * lastPos and post-teleport lastPos provably different values, so only the FIXED
     * code (which re-captures lastPos from the dragon's final, post-teleport position)
     * can pass. Fails on pre-fix HEAD (the binding keeps the stale bind-time position,
     * which is the OLD player position); passes after the fix.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void sameDimensionTeleportRefreshesDragonInstanceBinding(ExtendedGameTestHelper helper) {
        var bindPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(20, 2, 20)));

        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveTo(bindPos.x, bindPos.y, bindPos.z);

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(20, 2, 20));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);
        var handler = PlayerStateUtils.getHandler(player);

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            int whistleId = ((DragonWhistleItem) whistle.get()).getColor().getId();
            if (whistleId == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // The player relocates after the bind — the binding's bind-time lastPos (still
        // bindPos) is now stale by construction, independent of anything the summon
        // does. Both this and the dragon's subsequent displacement below stay inside
        // the SAME already-loaded LARGE_TEMPLATE structure.
        var summonPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(20, 2, 60)));
        player.moveTo(summonPos.x, summonPos.y, summonPos.z);

        // 80 blocks from the player's NEW (summon-time) position (>64 =
        // BASE_FOLLOW_RANGE * FOLLOW_RANGE_MULTIPLIER = 32 * 2) — forces the TELEPORT
        // branch, which lands the dragon exactly on summonPos.
        var farPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(100, 2, 60)));
        dragon.setPos(farPos.x, farPos.y, farPos.z);

        boolean result = DragonWhistleHandler.callDragon(player);
        if (!result) {
            helper.fail("Failed to call dragon");
            return;
        }

        var updatedInstance = handler.getDragonInstance(index);
        if (updatedInstance == null) {
            helper.fail("Dragon instance binding was lost after summon");
            return;
        }

        if (updatedInstance.getLastPos() == null || updatedInstance.getLastPos().equals(BlockPos.containing(bindPos))) {
            helper.fail("Dragon instance binding's lastPos is still the stale BIND-time position " + bindPos
                    + " instead of being refreshed to the post-teleport position");
            return;
        }

        if (!updatedInstance.getLastPos().equals(dragon.blockPosition())) {
            helper.fail("Dragon instance binding's lastPos was not refreshed to the post-teleport position: expected "
                    + dragon.blockPosition() + " but got " + updatedInstance.getLastPos());
        }

        helper.succeed();
    }

    /**
     * W8-SUMMON-2 (null-UUID hardening) regression: {@code
     * DragonOwnerCapability#isBoundToWhistle} must not NPE when one of the player's
     * {@link DragonInstance} entries has a null dragonUUID (a legacy entry that
     * predates the "uuid" NBT key). Fails on pre-fix HEAD with an NPE from
     * {@code instance.getUUID().equals(...)}; passes (returns false, since the
     * null-UUID entry cannot possibly match) after the fix.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void isBoundToWhistleToleratesNullUuidInstance(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var handler = PlayerStateUtils.getHandler(player);
        // A legacy-shaped entry: real entityId, but no recorded dragonUUID.
        handler.dragonInstances.put(0, new DragonInstance("minecraft:overworld", UUID.randomUUID(), null));

        boolean bound;
        try {
            bound = handler.isBoundToWhistle(dragon);
        } catch (NullPointerException e) {
            helper.fail("isBoundToWhistle NPE'd on a DragonInstance with a null dragonUUID: " + e);
            return;
        }

        if (bound) {
            helper.fail("isBoundToWhistle incorrectly matched a null-UUID instance against a real dragon's UUID");
        }

        helper.succeed();
    }

    /**
     * Invokes the private {@code respawnDragonFromSnapshot(Player, DragonOwnerCapability,
     * int)} directly via reflection — the concrete seam these W8-SYNC-4a tests need.
     * Bypassing {@code callDragon}'s public entry point is deliberate: that path only
     * ever reaches {@code respawnDragonFromSnapshot} with a valid (non-null
     * dimension/lastPos) binding via the deferred-summon pool's 60-tick timeout (see
     * {@code processDeferredSummons}), which is exactly the tick-window-timing shape
     * this repo's KNOWN-FLAKY {@code deathRespawnMintIsNotFlagged} already exhibits
     * (per C7, a new test should not knowingly inherit that same fragility when a
     * deterministic seam exists).
     */
    private static boolean invokeRespawnDragonFromSnapshot(Player player, DragonOwnerCapability cap, int index) {
        try {
            Method method = DragonWhistleHandler.class.getDeclaredMethod(
                    "respawnDragonFromSnapshot", Player.class, DragonOwnerCapability.class, int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, player, cap, index);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke respawnDragonFromSnapshot via reflection", e);
        }
    }

    /**
     * W8-SYNC-4a (C5 keystone) regression: {@code respawnDragonFromSnapshot} must
     * restore the pre-mint whistle-binding triple (dragonNBTs/dragonInstances/
     * lastSummons at this index) exactly when {@code addFreshEntity} refuses the mint,
     * rather than leaving {@code lastSummons} pointed at the discarded, never-joined
     * clone. Refusal is forced via a temporary {@code EntityJoinLevelEvent} listener
     * that cancels the join of any dragon carrying this slot's dragonUUID —
     * {@code createDragonEntity} stamps the clone with the SAME dragonUUID as the
     * original, so this is exactly the join the fix must recover from.
     *
     * <p>
     * Fails on pre-fix HEAD: {@code lastSummons[index]} is left pointing at the
     * discarded clone's random real UUID instead of the original dragon's; passes
     * after the fix (restored to the pre-mint values, byte-for-byte).
     *
     * <p>
     * Not asserted (no capture seam exists anywhere in this test suite — see this
     * wave's commits 10-12 red-baseline entries for the same, repeatedly-accepted
     * limitation): that no {@code DragonStatePacket} was sent, and that the player
     * received {@code dmr.dragon_call.not_found}. Both are real consequences of the
     * `return false` this test does verify was taken (a `return true` further down
     * would have sent the packet and skipped the not_found message), so the covered
     * assertions are not vacuous with respect to those omissions.
     *
     * <p>
     * Uses {@link #LARGE_TEMPLATE}, not the usual small {@code floor = true} platform:
     * {@code respawnDragonFromSnapshot}'s own presence gate ({@code
     * decideSnapshotRespawn}) requires EVERY chunk in a widened search box (up to 9x9
     * chunks around the binding's {@code lastPos}, sized off {@code
     * DRAGON_SEARCH_RADIUS}) to already be entity-loaded before it will even attempt a
     * mint — on the small default footprint most of that box falls outside the
     * generated/loaded structure, so the gate refuses EARLY (before ever touching
     * {@code createDragonEntity}) for a reason having nothing to do with this fix, and
     * this test's own restore assertions would then trivially (vacuously) pass because
     * the triple was never touched in the first place. Placing the dragon near the
     * center of a large, fully-loaded structure avoids that trap entirely.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void respawnRefusedMintRestoresPreMintBindingTriple(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        var centerPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(80, 2, 80)));
        player.moveTo(centerPos.x, centerPos.y, centerPos.z);

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(80, 2, 80));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        var dragonUuid = dragon.getDragonUUID();

        // Pre-mint triple, captured exactly as the fix must restore it.
        var preNbt = cap.dragonNBTs.get(index);
        var preInstance = cap.dragonInstances.get(index);
        var preLastSummon = cap.lastSummons.get(index);

        if (preNbt == null || preInstance == null || preLastSummon == null) {
            helper.fail("Setup failed: bind did not populate all three whistle-state maps for index " + index);
            return;
        }

        // Make the ORIGINAL unresolvable so respawnDragonFromSnapshot's own
        // decideSnapshotRespawn presence gate allows a mint attempt at all — mirrors
        // the real "chunk unloaded / dragon genuinely missing" trigger this path
        // exists for.
        dragon.discard();

        Consumer<EntityJoinLevelEvent> cancelMint = event -> {
            if (event.getEntity() instanceof TameableDragonEntity candidate
                    && dragonUuid.equals(candidate.getDragonUUID())) {
                event.setCanceled(true);
            }
        };

        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancelMint);
        boolean result;
        try {
            result = invokeRespawnDragonFromSnapshot(player, cap, index);
        } finally {
            NeoForge.EVENT_BUS.unregister(cancelMint);
        }

        if (result) {
            helper.fail("respawnDragonFromSnapshot returned true despite the mint's join being cancelled");
            return;
        }

        if (!preNbt.equals(cap.dragonNBTs.get(index))) {
            helper.fail("dragonNBTs[" + index + "] was not restored to its pre-mint value after a refused mint");
            return;
        }

        var restoredInstance = cap.dragonInstances.get(index);
        if (restoredInstance == null || !preInstance.writeNBT().equals(restoredInstance.writeNBT())) {
            helper.fail("dragonInstances[" + index + "] was not restored to its pre-mint value after a refused"
                    + " mint (still points at the discarded clone)");
            return;
        }

        if (!preLastSummon.equals(cap.lastSummons.get(index))) {
            helper.fail("lastSummons[" + index + "] was not restored to the ORIGINAL dragon's real UUID after a"
                    + " refused mint — still " + cap.lastSummons.get(index) + ", the discarded clone's UUID."
                    + " This is the C5 hazard: a later chunk load of the REAL dragon would be dedup-cancelled"
                    + " under duplicate_resolution=AGGRESSIVE.");
            return;
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-4a companion regression: proves the restore in
     * {@link #respawnRefusedMintRestoresPreMintBindingTriple} is not just internally
     * consistent but actually fixes the C5 failure mode it exists to prevent — after a
     * refused mint, the ORIGINAL dragon (same real entity UUID, same dragonUUID) must
     * still be accepted when it later rejoins (e.g. its chunk reloads), rather than
     * being dedup-cancelled because {@code lastSummons} was left pointing at the
     * discarded clone.
     *
     * <p>
     * Explicitly forces {@code ServerConfig.DUPLICATE_RESOLUTION = AGGRESSIVE} for the
     * duration of the test (default is {@code LOG}, which never cancels a join at all
     * — under the default, this test would pass identically whether or not the
     * restore happened, i.e. be vacuous). AGGRESSIVE is the resolution
     * {@code respawnDragonFromSnapshot}'s own javadoc names as the one this fix
     * protects against.
     *
     * <p>
     * Uses {@link #LARGE_TEMPLATE} for the same reason
     * {@link #respawnRefusedMintRestoresPreMintBindingTriple} does — see that test's
     * javadoc.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void refusedMintRestoreStillAllowsOriginalDragonToRejoin(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        var centerPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(80, 2, 80)));
        player.moveTo(centerPos.x, centerPos.y, centerPos.z);

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(80, 2, 80));
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        var originalEntityId = dragon.getUUID();
        var dragonUuid = dragon.getDragonUUID();

        dragon.discard();

        Consumer<EntityJoinLevelEvent> cancelMint = event -> {
            if (event.getEntity() instanceof TameableDragonEntity candidate
                    && dragonUuid.equals(candidate.getDragonUUID())) {
                event.setCanceled(true);
            }
        };

        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancelMint);
        try {
            invokeRespawnDragonFromSnapshot(player, cap, index);
        } finally {
            NeoForge.EVENT_BUS.unregister(cancelMint);
        }

        // Simulate the ORIGINAL dragon rejoining (its chunk reloads, or a cross-
        // dimension transit lands it back): same real entity UUID, same dragonUUID.
        // Built and UUID-stamped BEFORE ever touching the level, so there is no
        // level-side UUID index to desync (unlike setUUID after an add).
        var rejoined = ModEntities.DRAGON_ENTITY.get().create(helper.getLevel());
        if (rejoined == null) {
            helper.fail("Failed to construct the rejoining dragon entity");
            return;
        }
        rejoined.setBreed(DragonBreedsRegistry.getDefault());
        rejoined.setDragonUUID(dragonUuid);
        rejoined.setUUID(originalEntityId);
        rejoined.setOwnerUUID(player.getUUID());
        var rejoinPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(80, 2, 80)));
        rejoined.setPos(rejoinPos.x, rejoinPos.y, rejoinPos.z);

        var previousResolution = ServerConfig.DUPLICATE_RESOLUTION;
        ServerConfig.DUPLICATE_RESOLUTION = ServerConfig.DuplicateResolution.AGGRESSIVE;
        boolean added;
        try {
            added = helper.getLevel().addFreshEntity(rejoined);
        } finally {
            ServerConfig.DUPLICATE_RESOLUTION = previousResolution;
        }

        if (!added) {
            helper.fail("The ORIGINAL dragon (real UUID " + originalEntityId + ") was refused on rejoin after a"
                    + " refused respawn mint — the binding was NOT correctly restored, so"
                    + " DragonWhistleEvent's dedup check treated the real dragon as the duplicate (C5"
                    + " violation: permanent dragon loss under duplicate_resolution=AGGRESSIVE)");
            return;
        }

        helper.succeed();
    }

    // -----------------------------------------------------------------------------
    // W8-SYNC-4b+4c (commit 15): /dmr recall and /dmr spawn
    // -----------------------------------------------------------------------------

    /**
     * Minimal {@link CommandSource} that records every message routed through it,
     * rather than requiring a real network connection. {@code CommandSourceStack
     * #sendSuccess}/{@code #sendFailure} both funnel into {@code CommandSource
     * #sendSystemMessage} unconditionally once {@code acceptsSuccess()}/{@code
     * acceptsFailure()} return {@code true} (verified against the decompiled
     * source) — a genuinely concrete, implementable capture seam, unlike the
     * packet/client-message sends elsewhere in this wave (see commits 10-12 and
     * this commit's own not-asserted note above) which have none.
     */
    private static final class CapturingCommandSource implements CommandSource {
        final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            messages.add(component);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static CommandSourceStack makeCapturingCommandSourceStack(
            ExtendedGameTestHelper helper, Player player, CapturingCommandSource capture) {
        return new CommandSourceStack(
                capture,
                player.position(),
                Vec2.ZERO,
                helper.getLevel(),
                2,
                "test",
                Component.literal("test"),
                helper.getLevel().getServer(),
                player);
    }

    private static int invokeSpawnDragon(CommandSourceStack source, String breedName, Vec3 position, CompoundTag nbt) {
        try {
            Method method = DMRCommand.class.getDeclaredMethod(
                    "spawnDragon", CommandSourceStack.class, String.class, Vec3.class, CompoundTag.class);
            method.setAccessible(true);
            return (int) method.invoke(null, source, breedName, position, nbt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke spawnDragon via reflection", e);
        }
    }

    private static int invokeRunRecall(CommandSourceStack source, UUID id, Vec3 position) {
        try {
            Method method =
                    DMRCommand.class.getDeclaredMethod("runRecall", CommandSourceStack.class, UUID.class, Vec3.class);
            method.setAccessible(true);
            return (int) method.invoke(null, source, id, position);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke runRecall via reflection", e);
        }
    }

    /**
     * W8-SYNC-4b+4c regression: {@code /dmr spawn} must mint with a fresh random real
     * UUID rather than preserving whatever "UUID" tag a pasted NBT snapshot happens to
     * carry (mirrors {@code DragonOwnerCapability#createDragonEntity}'s existing
     * precedent). Fails on pre-fix HEAD (the pasted UUID survives {@code load(nbt)}
     * untouched); passes after the fix.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void spawnDragonMintsFreshRealUuidRatherThanThePastedNbtUuid(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var pastedUuid = UUID.randomUUID();
        var nbt = new CompoundTag();
        nbt.putUUID("UUID", pastedUuid);

        var capture = new CapturingCommandSource();
        var source = makeCapturingCommandSourceStack(helper, player, capture);

        int result = invokeSpawnDragon(source, DragonBreedsRegistry.getDefault().getId(), player.position(), nbt);

        if (result != 1) {
            helper.fail("spawnDragon reported failure (" + result + ") for a plain, uncontested spawn");
            return;
        }

        var spawned = helper.getLevel().getEntities(ModEntities.DRAGON_ENTITY.get(), d -> true);
        if (spawned.isEmpty()) {
            helper.fail("spawnDragon reported success but no dragon entity exists in the level");
            return;
        }

        boolean keptPastedUuid = spawned.stream().anyMatch(d -> pastedUuid.equals(d.getUUID()));
        if (keptPastedUuid) {
            helper.fail("Spawned dragon kept the pasted NBT's real UUID (" + pastedUuid
                    + ") instead of being minted with a fresh random one");
            return;
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-4b+4c regression: {@code /dmr spawn} must report failure, not success,
     * when the entity's join is refused (today it reports success unconditionally
     * once {@code dragonEntity instanceof TameableDragonEntity} — even if
     * {@code create()} returned a non-dragon, or the join itself was cancelled).
     * Refusal forced the same way as commit 14's tests: a temporary {@code
     * EntityJoinLevelEvent} listener. Fails on pre-fix HEAD ({@code spawnDragon}
     * returns 1 regardless); passes after the fix (returns 0).
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void spawnDragonReportsFailureOnRefusedJoin(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        Consumer<EntityJoinLevelEvent> cancelDragonJoins = event -> {
            if (event.getEntity() instanceof TameableDragonEntity) {
                event.setCanceled(true);
            }
        };

        var capture = new CapturingCommandSource();
        var source = makeCapturingCommandSourceStack(helper, player, capture);

        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancelDragonJoins);
        int result;
        try {
            result = invokeSpawnDragon(
                    source, DragonBreedsRegistry.getDefault().getId(), player.position(), new CompoundTag());
        } finally {
            NeoForge.EVENT_BUS.unregister(cancelDragonJoins);
        }

        if (result == 1) {
            helper.fail("spawnDragon returned success (1) despite the entity's join being cancelled");
            return;
        }

        if (capture.messages.isEmpty()) {
            helper.fail("spawnDragon's refusal path sent no feedback message at all");
            return;
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-4b+4c regression: {@code /dmr recall} must report failure, not success,
     * when the entity's join is refused (today it unconditionally {@code
     * sendSuccess}es after the mint attempt, regardless of whether
     * {@code addFreshEntity} actually accepted it). Fails on pre-fix HEAD
     * ({@code runRecall} returns success-shaped output regardless of the cancelled
     * join); passes after the fix.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void runRecallReportsFailureOnRefusedJoin(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        var dragonUuid = dragon.getDragonUUID();

        DragonWorldDataManager.addDragonHistory(dragon);
        // The pre-check loop in runRecall refuses if a LIVE dragon with this
        // dragonUUID already exists anywhere — discard so that check does not refuse
        // for a reason unrelated to what this test targets.
        dragon.discard();

        Consumer<EntityJoinLevelEvent> cancelDragonJoins = event -> {
            if (event.getEntity() instanceof TameableDragonEntity candidate
                    && dragonUuid.equals(candidate.getDragonUUID())) {
                event.setCanceled(true);
            }
        };

        var capture = new CapturingCommandSource();
        var source = makeCapturingCommandSourceStack(helper, player, capture);

        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancelDragonJoins);
        int result;
        try {
            result = invokeRunRecall(source, dragonUuid, player.position());
        } finally {
            NeoForge.EVENT_BUS.unregister(cancelDragonJoins);
        }

        if (result != 0) {
            helper.fail("runRecall did not report the refused join as a failure (returned " + result + ")");
            return;
        }

        var stillPresent = helper.getLevel()
                .getEntities(ModEntities.DRAGON_ENTITY.get(), d -> dragonUuid.equals(d.getDragonUUID()));
        if (!stillPresent.isEmpty()) {
            helper.fail("A dragon with dragonUUID " + dragonUuid
                    + " exists in the level despite its join having been cancelled");
            return;
        }

        if (capture.messages.isEmpty()) {
            helper.fail("runRecall's refusal path sent no feedback message at all");
            return;
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-4b+4c regression: closes the cross-level real-UUID collision the gate
     * required NOT be parked. {@code runRecall} stamps the recalled entity's REAL
     * UUID with the caller-supplied {@code id} ({@code dragon.setUUID(id)}) — this is
     * the only DMR minting path where the caller supplies the real UUID directly
     * rather than DMR generating a fresh random one, so it is the only
     * DMR-manufacturable way to end up with two live entities sharing a real UUID
     * server-wide. The existing dragonUUID-based pre-check cannot catch this: the
     * colliding entity here is a plain, unrelated entity with no dragonUUID at all.
     * Fails on pre-fix HEAD (the pre-check loop only compares dragonUUID, never the
     * real UUID {@code id} itself is about to be stamped with); passes after the fix.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void runRecallRefusesOnCrossLevelRealUuidCollision(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        var dragonUuid = dragon.getDragonUUID();

        DragonWorldDataManager.addDragonHistory(dragon);
        dragon.discard();

        // A plain, unrelated entity (no dragonUUID at all) already holding the exact
        // real UUID the recall is about to stamp onto its own mint. Built and
        // UUID-stamped BEFORE ever touching the level, matching commit 14's own
        // "no level-side UUID index to desync" precedent.
        var colliding = EntityType.PIG.create(helper.getLevel());
        if (colliding == null) {
            helper.fail("Failed to construct the colliding entity");
            return;
        }
        colliding.setUUID(dragonUuid);
        var collidingPos = Vec3.atCenterOf(helper.absolutePos(DMRTestConstants.TEST_POS.offset(2, 0, 0)));
        colliding.setPos(collidingPos.x, collidingPos.y, collidingPos.z);
        if (!helper.getLevel().addFreshEntity(colliding)) {
            helper.fail("Setup failed: could not add the colliding entity to the level");
            return;
        }

        var capture = new CapturingCommandSource();
        var source = makeCapturingCommandSourceStack(helper, player, capture);

        int result = invokeRunRecall(source, dragonUuid, player.position());

        if (result != 0) {
            helper.fail("runRecall did not refuse a real-UUID collision with an unrelated (non-dragon, no"
                    + " dragonUUID) entity already holding that UUID (returned " + result + ")");
            return;
        }

        var minted = helper.getLevel()
                .getEntities(ModEntities.DRAGON_ENTITY.get(), d -> dragonUuid.equals(d.getDragonUUID()));
        if (!minted.isEmpty()) {
            helper.fail("runRecall minted a dragon despite the real-UUID collision refusal");
            return;
        }

        helper.succeed();
    }
}
