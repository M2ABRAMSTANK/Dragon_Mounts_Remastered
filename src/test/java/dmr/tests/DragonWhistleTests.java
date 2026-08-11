package dmr.tests;

import dmr.DMRTestConstants;
import dmr.DragonMounts.common.capability.DragonOwnerCapability;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler.DragonInstance;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.network.packets.DragonCommandPacket;
import dmr.DragonMounts.network.packets.DragonCommandPacket.Command;
import dmr.DragonMounts.registry.DragonBreedsRegistry;
import dmr.DragonMounts.registry.ModCapabilities;
import dmr.DragonMounts.registry.ModEntities;
import dmr.DragonMounts.registry.ModItems;
import dmr.DragonMounts.server.ai.DragonAI;
import dmr.DragonMounts.server.commands.DMRCommand;
import dmr.DragonMounts.server.entity.DragonAgroState;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.items.DragonWhistleItem;
import dmr.DragonMounts.server.worlddata.DragonWorldDataManager;
import dmr.DragonMounts.util.PlayerStateUtils;
import io.netty.channel.embedded.EmbeddedChannel;
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
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
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
import org.jetbrains.annotations.Nullable;

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
     * Tests that a dragon follows the player when called with a whistle (walk
     * branch — the dragon stays out of sight and closes distance under its own AI,
     * as opposed to {@link #fortyBlockSummonUsesTeleportBranchNotWalkBranch}'s
     * teleport branch).
     *
     * <p>
     * W8-SUMMON-5 (de-vacuum): the original version displaced the dragon by
     * {@code +5,+5} (~7.07 blocks) — already INSIDE {@code ServerConfig
     * .MAX_FOLLOW_DISTANCE} (8), so {@code StayCloseToTarget}'s "too close" cutoff
     * meant the follow AI never needed to run at all; the {@code > 10} assertion
     * passed on the setup alone, before a single tick of AI ran. Rewritten per the
     * gate's amendment to a 14-block SINGLE-axis displacement (comfortably outside
     * {@code MAX_FOLLOW_DISTANCE}, well inside the default 32-block walk band) and a
     * MONOTONE PROGRESS assertion — final separation strictly less than initial,
     * and within {@code MAX_FOLLOW_DISTANCE} plus a small margin — rather than
     * convergence within a hard tick cap. Driven via {@code helper.onEachTick}/
     * {@code helper.succeedWhen} (this file's own established pattern for
     * AI-dependent convergence, e.g. {@code DragonTests#willNotAttackTamed}), tying
     * each check to a genuine server tick. The test's default 100-tick
     * {@code @GameTest} timeout (unmodified — no DMR test override, per commit 8's
     * own established precedent) IS the hard cap: if a genuine regression makes
     * convergence slower than that, the test times out and fails loudly — correct
     * behavior, not a poll-forever.
     *
     * <p>
     * {@code @EmptyTemplate(LARGE_TEMPLATE)}, not the file's usual small
     * {@code floor = true} platform: this test's first RED attempt (14-block
     * single-axis displacement, monotone-progress assertion, on the default 3x3x3
     * footprint) stayed stuck at exactly the initial 14.0-block separation for the
     * full 100-tick window — empirically confirmed (see red-baseline.md) to be
     * because the default footprint leaves no generated/loaded terrain past ~1.5
     * blocks from spawn, so the dragon simply cannot compute a path to a target
     * that far outside it; this is indistinguishable from a genuine walk-branch
     * regression without the isolation check below. A follow-up isolation run
     * (same footprint bug fixed, but reverted to a manual synchronous {@code for}
     * loop calling {@code tick()} 100 times within one real gametest tick) also
     * PASSED — disproving an earlier draft of this comment that attributed the
     * original failure to {@code level.getGameTime()} not advancing across manual
     * calls. The manual loop works fine once the terrain is real; {@code
     * onEachTick} is kept regardless purely for consistency with this file's other
     * AI-convergence tests, not because it is load-bearing for this test's
     * correctness.
     *
     * <p>
     * Walk-branch convergence QUALITY (how fast, how reliably) is owned by the
     * pathfind area this wave (streamlinePath's beeline bug, the 5-tick {@code
     * createPath} throttle, etc. — see refute-summon.json missedBugs #1/#2/#4);
     * coupling this summon-area test to another area's fixes landing in the same
     * wave is an accepted release risk, per the gate's explicit instruction to state
     * it in writing rather than leave it silent.
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(LARGE_TEMPLATE)
    @GameTest
    @TestHolder
    public static void dragonFollowsWhenCalled(ExtendedGameTestHelper helper) {
        // LARGE_TEMPLATE, not the usual small floor=true platform (see that constant's
        // javadoc): this test needs the dragon to actually WALK 14 real blocks across
        // pathable ground over dozens of ticks, and the default 3x3x3 footprint leaves
        // no generated/loaded terrain for a target that far out — the dragon simply
        // cannot path there and sits motionless, which is indistinguishable from a
        // genuine walk-branch regression (confirmed empirically: see red-baseline.md).
        // Both entities are placed near the structure's center so a 14-block
        // single-axis offset stays comfortably inside the loaded/generated area.
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        var centerPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(80, 2, 80)));
        player.moveTo(centerPos.x, centerPos.y, centerPos.z);

        // Spawn and tame a dragon
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), new BlockPos(80, 2, 80));
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

        // 14 blocks, single axis — comfortably outside MAX_FOLLOW_DISTANCE (8), well
        // inside the default 32-block walk band, so the WALK branch (not teleport) is
        // what actually gets exercised.
        dragon.setPos(dragon.getX() + 14, dragon.getY(), dragon.getZ());

        double initialSeparation = dragon.position().distanceTo(player.position());

        // Call the dragon
        boolean result = DragonWhistleHandler.callDragon(player);
        if (!result) {
            helper.fail("Failed to call dragon");
            return;
        }

        var followingDragon = DragonWhistleHandler.findDragon(player, 1);
        if (followingDragon == null) {
            helper.fail("Dragon did not resolve after being called — cannot distinguish a broken walk branch"
                    + " from a resolution miss");
            return;
        }

        helper.onEachTick(() -> {
            player.tick();
            followingDragon.tick();
        });

        helper.succeedWhen(() -> {
            double finalSeparation = followingDragon.position().distanceTo(player.position());
            helper.assertTrue(
                    finalSeparation < initialSeparation,
                    "Dragon has made no progress toward the player after being called — separation went from "
                            + initialSeparation + " to " + finalSeparation);
            helper.assertTrue(
                    finalSeparation <= ServerConfig.MAX_FOLLOW_DISTANCE + 2,
                    "Dragon has not yet closed to within MAX_FOLLOW_DISTANCE (+2 margin) of the player —"
                            + " separation is " + finalSeparation);
        });
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
    // W8-SUMMON-5: PARKED, not resurrected. Root cause never diagnosed in this pass —
    // the leading suspect is the cumulative ~+-70-block SAME-DIMENSION displacement
    // (below) pushing dragons outside the gametest structure's loaded/generated chunk
    // range (mirrors, but is not identical to, DET-1's chunk-load-timing shape); a
    // second candidate is an unrelated interaction with setNoAi(true)/setNoGravity(true)
    // and the teleport branch. dragonFollowsWhenCalled (walk convergence) and
    // fortyBlockSummonUsesTeleportBranchNotWalkBranch (same-dimension teleport
    // boundary, commit 16) independently close the coverage gap this test was meant to
    // provide, WITHOUT depending on diagnosing this displacement/structure interaction
    // — do not re-enable this test believing it was silently fixed by either of those.
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
     * Fix-round (summon-behavior cluster) requiredChange #1: covers commit 16's
     * (W8-SUMMON-1a, {@code 8ffbbd7}) actual THRESHOLD FLIP at the walk/teleport
     * call site in {@code summonExistingDragon}, not just the pure {@code
     * isWithinWalkRange}/{@code walkSummonMaxDistance} helpers it delegates to.
     *
     * <p>
     * An adversarial review found every distance already exercised by this suite
     * sits outside the "discriminating band" the flip actually changed:
     * {@link #sameDimensionTeleportRefreshesDragonInstanceBinding} uses 80 blocks,
     * {@link #dragonFollowsWhenCalled} uses 14, and
     * {@link #walkBranchSummonSendsExactlyOneFeedbackMessage} uses 6 — none of
     * those falls inside 33-64, the band that used to WALK under the pre-fix
     * {@code DragonConstants.BASE_FOLLOW_RANGE * ModConstants.DragonConstants
     * .FOLLOW_RANGE_MULTIPLIER} (32 * 2 = 64) product and now TELEPORTS under the
     * post-fix default (0.0 override -&gt; live {@code generic.follow_range}
     * attribute, 32 by default, clamped by {@code walkSummonMaxDistance}). Proof:
     * reverting the call site back to that pre-fix constant product leaves every
     * one of the tests above, and all other suite tests, green. This test places
     * the dragon 40 blocks away — inside the OLD 0-64 walk band, outside the NEW
     * default 0-32 one — so it can only pass if the call site actually reads the
     * shrunk threshold rather than the old constant.
     *
     * <p>
     * Displacement is VERTICAL ({@code setPos} y+40, held with {@code
     * setNoGravity(true)}) rather than horizontal, matching commit 20's amended
     * spec for this same class of boundary test: it keeps the dragon in the SAME
     * chunk column as the player, so — unlike a large horizontal offset — it
     * cannot spill into unticketed/unloaded terrain or a neighbouring gametest's
     * structure (the leading suspect recorded against the parked
     * {@code callMultipleDragons}'s +-70-block displacement) and needs no
     * {@code LARGE_TEMPLATE}/chunk-ticketing workaround the way
     * {@link #sameDimensionTeleportRefreshesDragonInstanceBinding}'s 80-block
     * horizontal displacement did.
     *
     * <p>
     * The resolution precondition ({@code findDragon(player, idx) == dragon}) is
     * asserted BEFORE the immediate post-call distance check, so a
     * deferred-summon resolution miss (which lets {@code callDragon} return
     * {@code true} without teleporting anything — the exact false-negative shape
     * {@link #sameDimensionTeleportRefreshesDragonInstanceBinding} hit before its
     * template was widened) is distinguishable from a genuinely broken teleport
     * branch. No tick window is used or needed: the teleport happens
     * synchronously inside {@code callDragon}, so this is C7-clean with no
     * tick-window absence assertion.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void fortyBlockSummonUsesTeleportBranchNotWalkBranch(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        // getDragonSummonIndex (callDragon's own gate) requires the player to
        // actually be HOLDING a whistle whose color id matches the bound index.
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // VERTICAL displacement, same chunk column as the player: 40 blocks is
        // inside the pre-fix 0-64 walk band and outside the post-fix default 0-32
        // one — the exact band this fix-round found uncovered. setNoGravity is
        // precautionary (this test never ticks the dragon, so gravity cannot act
        // before the synchronous callDragon call below), mirroring the existing
        // precedent in the parked callMultipleDragons test.
        dragon.setNoGravity(true);
        dragon.setPos(dragon.getX(), dragon.getY() + 40, dragon.getZ());

        boolean called = DragonWhistleHandler.callDragon(player);
        if (!called) {
            helper.fail("callDragon reported failure for a plain, uncontested 40-block summon");
            return;
        }

        var resolved = DragonWhistleHandler.findDragon(player, index);
        if (resolved != dragon) {
            helper.fail("callDragon did not resolve back to the original dragon (a deferred-summon"
                    + " resolution miss) — cannot distinguish a broken teleport branch from a resolution"
                    + " miss until this precondition holds");
            return;
        }

        double distance = dragon.position().distanceTo(player.position());
        if (distance >= 2) {
            helper.fail("A 40-block summon (inside the pre-fix 0-64 walk band, outside the post-fix"
                    + " default 0-32 one) did not teleport the dragon to the player — distance after"
                    + " callDragon was " + distance + " (expected < 2). A call site still reading the old"
                    + " BASE_FOLLOW_RANGE*FOLLOW_RANGE_MULTIPLIER product (commit 16 / W8-SUMMON-1a"
                    + " reverted) would take the WALK branch here instead and leave the dragon far away.");
            return;
        }

        helper.succeed();
    }

    /**
     * Fix-round (summon-behavior cluster) requiredChange #3: {@link
     * #fortyBlockSummonUsesTeleportBranchNotWalkBranch} covers the DISTANCE half of
     * commit 16's threshold flip, but with {@code ServerConfig.SUMMON_WALK_MAX_DISTANCE}
     * left at its default (0.0 = "derive from follow range"), that test passes
     * identically whether the call site reads {@code ServerConfig.SUMMON_WALK_MAX_DISTANCE}
     * or a literal {@code 0.0} — the C8 escape hatch's wiring itself is uncovered
     * (same "helper covered, call site not" shape the previous review round rejected).
     *
     * <p>
     * This test sets {@code ServerConfig.SUMMON_WALK_MAX_DISTANCE = 64.0} (restoring
     * the pre-wave-8 64-block radius) in a try/finally, repeats the identical 40-block
     * vertical setup, and asserts the OPPOSITE outcome: the dragon does NOT teleport
     * (walk branch taken, separation stays ~40). {@code
     * ModConstants.DragonConstants#walkSummonMaxDistance(64, 32)} resolves to {@code
     * min(64, pathfindSearchRadius(32)) = min(64, 64) = 64}, so 40 sits inside the
     * walk band under this override. RED-provable: a call site passing a literal
     * {@code 0.0} instead of {@code ServerConfig.SUMMON_WALK_MAX_DISTANCE} would still
     * resolve to the same default-derived 32-block threshold regardless of this
     * override, and 40 &gt; 32 would teleport — this test fails on that variant and
     * passes only when the config field is actually read at the call site.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void summonWalkMaxDistanceOverrideExtendsWalkBranch(ExtendedGameTestHelper helper) {
        double previous = ServerConfig.SUMMON_WALK_MAX_DISTANCE;
        try {
            ServerConfig.SUMMON_WALK_MAX_DISTANCE = 64.0;

            var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
            player.moveToCentre();

            var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
            dragon.setBreed(DragonBreedsRegistry.getDefault());
            dragon.tamedFor(player, true);

            var index = 0;
            DragonWhistleHandler.setDragon(player, dragon, index);

            var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
            cap.setPlayerInstance(player);

            // getDragonSummonIndex (callDragon's own gate) requires the player to
            // actually be HOLDING a whistle whose color id matches the bound index.
            for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
                if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                    break;
                }
            }

            // Identical VERTICAL displacement to fortyBlockSummonUsesTeleportBranchNotWalkBranch
            // — same chunk column, no gravity concern (never ticked before the
            // synchronous callDragon call below).
            dragon.setNoGravity(true);
            dragon.setPos(dragon.getX(), dragon.getY() + 40, dragon.getZ());

            boolean called = DragonWhistleHandler.callDragon(player);
            if (!called) {
                helper.fail("callDragon reported failure for a plain, uncontested 40-block summon");
                return;
            }

            var resolved = DragonWhistleHandler.findDragon(player, index);
            if (resolved != dragon) {
                helper.fail("callDragon did not resolve back to the original dragon (a deferred-summon"
                        + " resolution miss) — cannot distinguish a broken walk branch from a resolution"
                        + " miss until this precondition holds");
                return;
            }

            double distance = dragon.position().distanceTo(player.position());
            if (distance < 30) {
                helper.fail("A 40-block summon with SUMMON_WALK_MAX_DISTANCE=64 teleported the dragon"
                        + " (distance after callDragon was " + distance + ", expected ~40) — the config"
                        + " override is not reaching the call site (walk/teleport decision is reading a"
                        + " literal default instead of ServerConfig.SUMMON_WALK_MAX_DISTANCE).");
                return;
            }

            helper.succeed();
        } finally {
            ServerConfig.SUMMON_WALK_MAX_DISTANCE = previous;
        }
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
     * Fix-round (commit 16, {@code f2c33cd}) regression: a REFUSED mint must NOT
     * consume either of {@code DragonWhistleHandler#isConfirmedDead}'s one-shot death-signal stores
     * ({@code cap.respawnDelays} and the per-level {@code DragonWorldDataManager}
     * dead-dragon record) — consumption is deferred until {@code addFreshEntity}
     * actually succeeds, specifically so a refusal leaves the death signal intact for
     * the player's immediate retry.
     *
     * <p>
     * {@link #respawnRefusedMintRestoresPreMintBindingTriple} cannot cover this: it
     * makes the original unresolvable via a bare {@code dragon.discard()}, which sends
     * no death signal at all, so {@code isConfirmedDead} is false for that test and
     * neither the pre-fix "consume before the mint" call nor the post-fix "consume
     * after the mint" call is ever reached — the deferral is provably uncovered by it
     * (see this wave's red-baseline for the empirical proof: reverting only commit
     * 16's production hunk leaves all 114 gametests green).
     *
     * <p>
     * This test forces {@code isConfirmedDead} to true directly, via the same seams
     * {@code isConfirmedDead}'s own javadoc names as its two independent stores —
     * {@code cap.respawnDelays.put(index, 0)} for the capability-side half, and
     * {@link DragonWorldDataManager#setDragonDead} for the world-data half — rather
     * than relying on a real vanilla death event, which this test suite avoids for the
     * same tick-window-fragility reasons {@link #invokeRespawnDragonFromSnapshot}'s
     * javadoc gives. Both stores are seeded so the test exercises the full
     * {@code confirmedDead} branch, including the {@code clearWorldDeathRecord} half
     * that a respawnDelays-only seed would leave untouched (isConfirmedDead short-
     * circuits true on respawnDelays alone, but the consumption block clears BOTH
     * stores unconditionally once confirmedDead is true).
     *
     * <p>
     * Refusal is forced the same way {@link #respawnRefusedMintRestoresPreMintBindingTriple}
     * forces it: a temporary {@code EntityJoinLevelEvent} listener cancels the join of
     * the clone carrying this slot's dragonUUID.
     *
     * <p>
     * Fails on pre-fix-round HEAD (commit 15 / {@code e3703ce}, i.e. {@code f2c33cd^}):
     * both stores are consumed unconditionally right after {@code confirmedDead} is
     * computed, before {@code addFreshEntity} is even attempted, so a refused mint
     * still leaves {@code respawnDelays} empty and the world-data record cleared.
     * Passes after the fix: consumption only happens once the mint has actually
     * joined, so a refusal leaves both stores exactly as seeded.
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
    public static void refusedMintDoesNotConsumeDeathRecord(ExtendedGameTestHelper helper) {
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

        // Seed BOTH of isConfirmedDead's independent death-signal stores before the
        // original is discarded, so respawnDragonFromSnapshot computes confirmedDead
        // == true for this mint — DragonWhistleHandler.java:692's isConfirmedDead
        // returns true on respawnDelays.containsKey alone, and DragonWorldDataManager
        // .setDragonDead seeds the world-data half (DragonWhistleHandler.java:716's
        // clearWorldDeathRecord half).
        cap.respawnDelays.put(index, 0);
        DragonWorldDataManager.setDragonDead(dragon, "test-seeded-death-record");

        if (!cap.respawnDelays.containsKey(index)) {
            helper.fail("Setup failed: respawnDelays seed did not take");
            return;
        }
        if (!DragonWorldDataManager.isDragonDead(helper.getLevel(), dragonUuid)) {
            helper.fail("Setup failed: world-data death record seed did not take");
            return;
        }

        // Make the ORIGINAL unresolvable so respawnDragonFromSnapshot's own
        // decideSnapshotRespawn presence gate allows a mint attempt at all — mirrors
        // {@link #respawnRefusedMintRestoresPreMintBindingTriple}'s setup.
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

        if (!cap.respawnDelays.containsKey(index)) {
            helper.fail("A refused mint consumed cap.respawnDelays[" + index + "] anyway — a genuine death signal"
                    + " was permanently destroyed with no dragon minted, so the player's immediate retry will"
                    + " compute confirmedDead == false and mint an unflagged, reclaim-eligible clone of a dragon"
                    + " vanilla had already confirmed dead.");
            return;
        }

        if (!DragonWorldDataManager.isDragonDead(helper.getLevel(), dragonUuid)) {
            helper.fail("A refused mint cleared the world-data dead-dragon record for " + dragonUuid + " anyway —"
                    + " same C5/provenance hazard as the respawnDelays half, via the other one-shot death-signal"
                    + " store.");
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
     * <p>
     * The colliding entity is placed in the NETHER — a DIFFERENT level from the one
     * the recall mints into (the overworld, via {@code helper.getLevel()}) — so that
     * vanilla's own per-level {@code PersistentEntitySectionManager} UUID index (which
     * only guards its own level) cannot be what refuses the join; only DMRCommand's
     * explicit all-levels {@code candidateLevel.getEntity(id) != null} scan can catch
     * a collision that lives in a level the mint never touches. Placing the collider
     * in the same level as the mint (as an earlier draft of this test did) would let
     * vanilla's own index silently do the refusing instead, leaving the actual
     * cross-level check uncovered. The test also asserts on the specific collision
     * failure message, not just a non-zero-refusal return code, so a refusal from the
     * unrelated join-cancelled/no-history path can't accidentally pass this test.
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

        ServerLevel netherLevel = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (netherLevel == null) {
            helper.fail("Setup failed: the gametest server has no NETHER level loaded");
            return;
        }

        // Force the target chunk to actually exist AND become entity-visible before
        // adding an entity to it directly — unlike the changeDimension path other
        // tests in this file use, there is no portal plumbing here to do this for us.
        // getChunk() alone only forces block/data generation up to FULL status; the
        // entity-visibility promotion that ServerLevel#getEntity(UUID) reads from
        // (PersistentEntitySectionManager's visibleEntityStorage) is driven by
        // ChunkMap's distance-manager ticket tracking, not synchronously by getChunk()
        // itself — a plain getChunk() call with no region ticket registered gets torn
        // back down (scheduled for unload) the moment the chunk source is next ticked,
        // so the entity we add afterward never gets promoted. A FORCED ticket at
        // distance 0 (-> ticket level 33, ChunkLevel.byStatus(FULL)) keeps the chunk at
        // FullChunkStatus.FULL — Visibility.TRACKED, i.e. accessible via getEntity(...)
        // — for as long as the ticket is held; a few chunk-source ticks afterward let
        // the distance manager actually process it and promote the chunk.
        ChunkPos colliderChunkPos = new ChunkPos(0, 0);
        netherLevel.getChunkSource().addRegionTicket(TicketType.FORCED, colliderChunkPos, 0, colliderChunkPos);
        try {
            netherLevel.getChunk(0, 0);
            // Registering the ticket only queues a level-change; ChunkMap's actual
            // promotion to an ACCESSIBLE visibility runs as a completable-future
            // continuation (ChunkHolder#scheduleFullChunkPromotion) chained onto the
            // level's own chunk-source main-thread executor. A few #tick() passes let
            // the distance manager propagate the ticket and schedule that
            // continuation; draining the executor's task queue afterward via the
            // PUBLIC #pollTask (the same executor #getChunk's own blocking wait pumps
            // internally) is what actually RUNS it — synchronously, with no dependency
            // on a real server tick ever occurring.
            for (int i = 0; i < 5; i++) {
                netherLevel.getChunkSource().tick(() -> true, true);
            }
            while (netherLevel.getChunkSource().pollTask()) {
                // Drain until the queue is empty.
            }

            // A plain, unrelated entity (no dragonUUID at all) already holding the
            // exact real UUID the recall is about to stamp onto its own mint — added
            // to the NETHER, NOT the overworld level the recall mints into, so only
            // DMRCommand's explicit all-levels real-UUID scan (not vanilla's per-level
            // UUID index) can refuse this. Built and UUID-stamped BEFORE ever touching
            // the level, matching commit 14's own "no level-side UUID index to
            // desync" precedent.
            var colliding = EntityType.PIG.create(netherLevel);
            if (colliding == null) {
                helper.fail("Failed to construct the colliding entity");
                return;
            }
            colliding.setUUID(dragonUuid);
            colliding.setPos(0, 64, 0);
            if (!netherLevel.addFreshEntity(colliding)) {
                helper.fail("Setup failed: could not add the colliding entity to the nether level");
                return;
            }

            if (netherLevel.getEntity(dragonUuid) == null) {
                helper.fail("Setup failed: the colliding entity was added to the nether but is not visible via"
                        + " getEntity(uuid) there — the forced chunk ticket did not promote it in time");
                return;
            }

            var capture = new CapturingCommandSource();
            var source = makeCapturingCommandSourceStack(helper, player, capture);

            int result = invokeRunRecall(source, dragonUuid, player.position());

            if (result != 0) {
                helper.fail("runRecall did not refuse a real-UUID collision with an unrelated (non-dragon, no"
                        + " dragonUUID) entity already holding that UUID in a DIFFERENT level (returned " + result
                        + ")");
                return;
            }

            var minted = helper.getLevel()
                    .getEntities(ModEntities.DRAGON_ENTITY.get(), d -> dragonUuid.equals(d.getDragonUUID()));
            if (!minted.isEmpty()) {
                helper.fail("runRecall minted a dragon despite the real-UUID collision refusal");
                return;
            }

            boolean sawCollisionMessage = capture.messages.stream()
                    .map(Component::getString)
                    .anyMatch(msg -> msg.contains("refusing to recall a colliding real UUID"));
            if (!sawCollisionMessage) {
                helper.fail(
                        "runRecall refused, but not via the cross-level real-UUID collision check — expected a"
                                + " message containing \"refusing to recall a colliding real UUID\", got: "
                                + capture.messages);
                return;
            }

            helper.succeed();
        } finally {
            netherLevel.getChunkSource().removeRegionTicket(TicketType.FORCED, colliderChunkPos, 0, colliderChunkPos);
        }
    }

    // -----------------------------------------------------------------------------
    // W8-SUMMON-6 (commit 17): walk-branch summon feedback
    // -----------------------------------------------------------------------------

    /**
     * Drains every {@link ClientboundSystemChatPacket} queued for this mock player
     * since the last drain (or since connection). {@code
     * ExtendedGameTestHelper#makeTickingMockServerPlayerInLevel} wires the returned
     * {@code GameTestPlayer} to a real {@code Connection} sitting on an {@link
     * EmbeddedChannel} (via {@code NetworkRegistry#configureMockConnection} +
     * {@code PlayerList#placeNewPlayer} — verified against the decompiled sources of
     * both {@code net.minecraft.gametest.framework.GameTestHelper} and NeoForge's
     * {@code ExtendedGameTestHelper}). Unlike the command-dispatch path ({@link
     * CapturingCommandSource}), {@code Player#displayClientMessage} has no
     * purpose-built capture seam anywhere else in this test suite (see this wave's
     * commits 10-12 and 14 red-baseline entries, which each accepted "no capture seam
     * exists" as a real limitation) — but the EmbeddedChannel backing the mock
     * player's connection IS a concrete, already-wired one: {@code Connection#send}
     * forwards straight to {@code channel.writeAndFlush(packet)} with no encoder in
     * the test pipeline (the pipeline holds only the {@code Connection} handler
     * itself, which is inbound-only), so an outbound packet lands in the
     * EmbeddedChannel's outbound queue unmodified and is readable via {@code
     * readOutbound()} — the exact {@code Component} instance that was sent.
     *
     * <p>
     * The explicit {@code channel.flush()} below is load-bearing, not defensive
     * boilerplate: {@code MinecraftServer}'s tick loop calls {@code
     * connection.suspendFlushing()} on every player at the START of each tick and
     * {@code resumeFlushing()} (which itself flushes) at the END — a per-tick write
     * batching optimization. A gametest sequence step runs INSIDE that window, so
     * {@code ServerCommonPacketListenerImpl#send}'s own {@code flag} computation
     * (`!suspendFlushingOnServerThread || !server.isSameThread()`) resolves to
     * {@code flush=false} and the packet sits in the pipeline's unflushed write
     * buffer — invisible to {@code readOutbound()} — until the tick ends. Calling
     * {@code channel.flush()} directly (bypassing the Connection's own bookkeeping
     * entirely) forces it into the readable queue immediately. Confirmed empirically:
     * without this call, {@link #walkBranchSummonSendsExactlyOneFeedbackMessage}
     * observes zero messages even though the packet was genuinely sent (see
     * red-baseline.md).
     */
    private static List<Component> drainSystemChatMessages(ServerPlayer player) {
        var channel = (EmbeddedChannel) player.connection.getConnection().channel();
        channel.flush();
        List<Component> messages = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof ClientboundSystemChatPacket packet) {
                messages.add(packet.content());
            }
        }
        return messages;
    }

    /**
     * W8-SUMMON-6: closes the gate's "missing" item — refute-summon.json
     * missedBugs#3's second half. Pre-fix, the walk branch of {@code
     * summonExistingDragon} wrote {@code cap.lastSummons} and a debug log and sent
     * NOTHING to the player: the dragon is out of sight and may take a while to
     * arrive, so a player whistling from inside the walk band had no confirmation the
     * whistle did anything at all. Establishes the wave-wide invariant in code: every
     * summon outcome emits exactly ONE terminal player signal (cross-dimension/
     * teleport: the dragon visibly appearing next to the player; refused mint:
     * {@code dmr.dragon_call.not_found}; walk: this new action-bar message).
     *
     * <p>
     * Fails on pre-fix HEAD (zero {@code ClientboundSystemChatPacket}s queued after a
     * walk-band summon — see red-baseline.md for the captured failure); passes after
     * the fix (exactly one, carrying the {@code dmr.dragon_call.walking} translation
     * key).
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void walkBranchSummonSendsExactlyOneFeedbackMessage(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        // Reposition the already-spawned dragon a small, well-within-walk-band
        // distance from the player in ABSOLUTE coordinates (mirrors
        // dragonFollowsWhenCalled's precedent) — helper.spawn's BlockPos argument is
        // STRUCTURE-RELATIVE, not absolute, so offsetting player.blockPosition()
        // directly would place the dragon somewhere unrelated to the player instead.
        dragon.setPos(player.getX() + 6, player.getY(), player.getZ());

        // getDragonSummonIndex (callDragon's own gate) requires the player to
        // actually be HOLDING a whistle whose color id matches the bound index — the
        // capability-only binding above is not enough on its own.
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // Setup (placeNewPlayer's initial sync, moveToCentre's chunk-sender calls,
        // tamedFor's own feedback, ...) queues packets of its own — drain and discard
        // them so only the summon's OWN messages are counted below.
        drainSystemChatMessages(player);

        boolean called = DragonWhistleHandler.callDragon(player);
        if (!called) {
            helper.fail("callDragon reported failure for a plain, uncontested walk-band summon");
            return;
        }

        var messages = drainSystemChatMessages(player);
        var walkMessages = messages.stream()
                .filter(component -> component.getContents() instanceof TranslatableContents contents
                        && "dmr.dragon_call.walking".equals(contents.getKey()))
                .toList();

        if (walkMessages.isEmpty()) {
            helper.fail("Walk-branch summon sent no dmr.dragon_call.walking feedback — the player has no"
                    + " confirmation the whistle did anything (all system chat messages seen: " + messages + ")");
            return;
        }
        if (walkMessages.size() > 1) {
            helper.fail("Walk-branch summon sent " + walkMessages.size()
                    + " dmr.dragon_call.walking messages instead of exactly one: " + walkMessages);
            return;
        }

        helper.succeed();
    }

    // -----------------------------------------------------------------------------
    // W8-SUMMON-1b (commit 18): whistle recall grace
    // -----------------------------------------------------------------------------

    private record AggressiveDragonWithHostile(TameableDragonEntity dragon, Zombie hostile) {}

    /**
     * Spawns an AGGRESSIVE tamed dragon with a live {@code Zombie} inside {@link
     * dmr.DragonMounts.server.ai.sensors.DragonAttackablesSensor}'s tamed-dragon range
     * (distanceToSqr &lt;= 16.0, i.e. &lt;= 4 blocks) and seeds {@code
     * NEAREST_ATTACKABLE} with it directly — AGGRESSIVE is required for the sensor to
     * hunt at all for a tamed dragon, and the zombie matches the
     * {@code dragon_hunting_target} tag (UNDEAD) the sensor's own target predicate
     * checks, so the seeded value is exactly what the real sensor would independently
     * compute (not a fabricated shortcut). The direct seed exists because callers
     * invoke {@code createAttackInitiationBehavior} directly (see {@link
     * #invokeAttackInitiationTryStart}) rather than ticking the dragon and waiting on
     * the REAL {@code DragonAttackablesSensor} — that sensor only re-scans every ~20
     * ticks with vanilla {@code Sensor}'s own randomized initial offset, which made an
     * earlier tick-based version of these tests genuinely flaky (see red-baseline.md).
     *
     * <p>
     * Shared setup for both {@link #whistleRecallGraceBlocksAttackInitiation} and its
     * positive control {@link #attackInitiationStartsWithoutRecallGrace}. Fails the
     * test outright (returns {@code null}) if the hostile could not be constructed or
     * added — callers must check for that before proceeding.
     */
    @Nullable
    private static AggressiveDragonWithHostile spawnAggressiveDragonWithLiveHostileInRange(
            ExtendedGameTestHelper helper, ServerPlayer player) {
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);
        dragon.setAgroState(DragonAgroState.AGGRESSIVE);

        var hostile = EntityType.ZOMBIE.create(helper.getLevel());
        if (hostile == null) {
            helper.fail("Setup failed: could not construct the hostile mob");
            return null;
        }
        hostile.setPos(dragon.getX() + 2, dragon.getY(), dragon.getZ());
        if (!helper.getLevel().addFreshEntity(hostile)) {
            helper.fail("Setup failed: could not add the hostile mob to the level");
            return null;
        }
        dragon.getBrain().setMemory(MemoryModuleType.NEAREST_ATTACKABLE, hostile);
        return new AggressiveDragonWithHostile(dragon, hostile);
    }

    /**
     * Invokes {@code DragonAI.createAttackInitiationBehavior()}'s {@code tryStart} —
     * the EXACT behavior this commit gates with {@code !isInWhistleRecallGrace()} —
     * directly via reflection, bypassing {@code Brain} activity selection and
     * {@code Sensor} scan-rate timing entirely. This is deterministic where ticking
     * the dragon through its normal AI loop is not: {@code
     * BehaviorWrapper#tryStart} evaluates the gating predicate and (if it passes)
     * runs the wrapped {@code StartAttacking} synchronously, on the calling thread,
     * with no dependency on which {@code Activity} happens to be selected or on any
     * sensor having fired yet.
     *
     * <p>
     * Deliberately IGNORES the returned boolean: {@code BehaviorWrapper#tryStart}
     * returns {@code true} once the predicate and memory-presence gates pass,
     * regardless of whether the WRAPPED {@code StartAttacking} behavior itself
     * actually set {@code ATTACK_TARGET} — the memory state after the call is the
     * only reliable signal, which is what every caller here checks.
     */
    private static void invokeAttackInitiationTryStart(ServerLevel level, TameableDragonEntity dragon) {
        try {
            Method method = DragonAI.class.getDeclaredMethod("createAttackInitiationBehavior");
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            var behavior = (BehaviorControl<TameableDragonEntity>) method.invoke(null);
            behavior.tryStart(level, dragon, level.getGameTime());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke createAttackInitiationBehavior via reflection", e);
        }
    }

    /**
     * W8-SUMMON-1b: proves the grace window actually BLOCKS attack (re-)initiation,
     * not merely that the one-shot {@code eraseMemory(ATTACK_TARGET)} line exists
     * (the gate's explicit critique of the original design's zero-tick assertion,
     * which could not distinguish "the erase ran" from "the erase actually mattered").
     * The dragon is put into a simulated mid-fight state (ATTACK_TARGET pre-set to
     * the live hostile — a dragon with no target at all would trivially pass
     * regardless of the fix); the whistle is called (erasing ATTACK_TARGET and
     * arming the grace window); {@link #invokeAttackInitiationTryStart} is then
     * invoked directly — the exact behavior a subsequent brain tick would run — and
     * ATTACK_TARGET must remain empty. Companion positive control: {@link
     * #attackInitiationStartsWithoutRecallGrace} proves the IDENTICAL rig (same
     * setup helper, same direct invocation) DOES re-populate ATTACK_TARGET when no
     * grace is set — without it, this test's "still empty" assertion could pass
     * vacuously if the hostile-detection rig (tag/agro-state) were simply broken.
     *
     * <p>
     * Fails on pre-fix HEAD (StartAttacking re-selects the still-present hostile
     * immediately after the erase, with no grace guard to stop it — see
     * red-baseline.md for the captured failure); passes after the fix.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void whistleRecallGraceBlocksAttackInitiation(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var setup = spawnAggressiveDragonWithLiveHostileInRange(helper, player);
        if (setup == null) {
            return; // helper.fail already called
        }
        var dragon = setup.dragon();

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);
        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // Simulate mid-fight: a dragon already engaged in combat holds ATTACK_TARGET
        // (Activity.FIGHT) directly.
        dragon.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, setup.hostile());

        // Well inside the walk band — moves the PLAYER, not the dragon, so the
        // dragon/hostile distance (and therefore NEAREST_ATTACKABLE's validity) is
        // untouched by the summon.
        player.moveTo(dragon.getX() + 6, dragon.getY(), dragon.getZ());

        boolean called = DragonWhistleHandler.callDragon(player);
        if (!called) {
            helper.fail("callDragon reported failure for a plain, uncontested walk-band summon");
            return;
        }

        if (dragon.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
            helper.fail("The whistle did not erase ATTACK_TARGET at all — cannot exercise the grace guard");
            return;
        }

        invokeAttackInitiationTryStart((ServerLevel) helper.getLevel(), dragon);

        if (dragon.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
            helper.fail("createAttackInitiationBehavior set ATTACK_TARGET immediately after a whistle recall,"
                    + " despite the grace window — the grace guard did not hold");
            return;
        }

        helper.succeed();
    }

    /**
     * W8-SUMMON-1b positive control for {@link #whistleRecallGraceBlocksAttackInitiation}:
     * the IDENTICAL rig (AGGRESSIVE dragon, live in-range hostile seeded into
     * NEAREST_ATTACKABLE, ATTACK_TARGET erased) and the IDENTICAL direct invocation,
     * but with NO whistle call — so no grace window is ever armed — must have
     * ATTACK_TARGET (re-)populated. Without this test, the main test's "still empty"
     * assertion could pass vacuously if the hostile-detection rig (wrong tag, wrong
     * agro state, reflection seam broken) were simply non-functional rather than
     * because the grace guard actually did its job.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void attackInitiationStartsWithoutRecallGrace(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var setup = spawnAggressiveDragonWithLiveHostileInRange(helper, player);
        if (setup == null) {
            return; // helper.fail already called
        }
        var dragon = setup.dragon();

        // Same erase summonExistingDragon performs — but with NO grace window set,
        // so nothing suppresses createAttackInitiationBehavior's predicate.
        dragon.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);

        invokeAttackInitiationTryStart((ServerLevel) helper.getLevel(), dragon);

        var reacquired = dragon.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
        if (reacquired.isEmpty()) {
            helper.fail("ATTACK_TARGET was never reacquired without a recall grace — the hostile-detection rig"
                    + " (tag/agro-state) or the reflection seam is not actually functional, which would make the"
                    + " companion grace test's \"still empty\" assertion vacuous");
            return;
        }
        if (!reacquired.get().is(setup.hostile())) {
            helper.fail("ATTACK_TARGET was reacquired but points at a different entity than the seeded hostile: "
                    + reacquired.get());
            return;
        }

        helper.succeed();
    }

    // -----------------------------------------------------------------------------
    // W8-SUMMON-3 (commit 19): DragonCommandPacket findDragon routing, id-keyed
    // dispatch, throttle, WANDER cross-dimension guard
    // -----------------------------------------------------------------------------

    /**
     * summon-02: SIT/FOLLOW/WANDER/agro commands used to resolve their target dragon
     * via a bare {@code level.getEntity(instance.getEntityId())} real-UUID lookup — no
     * fallback at all. This faithfully reproduces summon-02's actual root-cause shape:
     * a stale {@code entityId} (exactly what W8-SUMMON-2 elsewhere prevents from
     * recurring, but must still be tolerated for legacy/edge data) paired with the
     * CORRECT {@code dragonUUID}. Fails on pre-fix HEAD (the naive lookup returns
     * {@code null}, the whole switch is skipped, the dragon stays sitting); passes
     * after the fix, which falls through to {@link DragonWhistleHandler#findDragon}'s
     * dragonUUID-verified widened-radius scan on a fast-path miss.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void followCommandResolvesWithStaleEntityIdBinding(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);
        dragon.setOrderedToSit(true);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        // Overwrite the binding with a random, NON-matching entityId but the CORRECT
        // dragonUUID — setDragon's own DragonInstance(dragon) constructor would have
        // written the correct entityId, so this deliberately corrupts just that field
        // to reproduce the stale-binding shape.
        cap.setDragonInstance(index, new DragonInstance(player.level, UUID.randomUUID(), dragon.getDragonUUID()));

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        new DragonCommandPacket(Command.FOLLOW).handleServer(null, player);

        if (dragon.isOrderedToSit()) {
            helper.fail("FOLLOW did not resolve the dragon through a stale-entityId binding — the dragon is"
                    + " still sitting. level.getEntity(randomUUID) must have returned null and the whole switch"
                    + " was skipped instead of falling through to findDragon.");
            return;
        }

        helper.succeed();
    }

    /**
     * Catches a regression that reintroduces an unguarded null-dereference on the
     * "dragon could not be resolved at all" path. Binds index 0 to a
     * {@link DragonInstance} whose {@code dragonUUID} matches no live entity anywhere
     * (neither the stored dimension's exact/widened scans nor the player-proximity
     * fallback can find it), issues SIT, and asserts no exception propagates.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void followCommandOnUnresolvableDragonDoesNotThrow(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        // Neither entityId nor dragonUUID matches the live dragon (or anything else) —
        // findDragon must come back empty everywhere, and the "not found" branch must
        // not NPE.
        cap.setDragonInstance(index, new DragonInstance(player.level, UUID.randomUUID(), UUID.randomUUID()));

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        try {
            new DragonCommandPacket(Command.SIT).handleServer(null, player);
        } catch (Exception e) {
            helper.fail("SIT on an unresolvable dragon binding threw instead of showing not_found feedback: " + e);
            return;
        }

        helper.succeed();
    }

    /**
     * constraint-a3: an out-of-range/malformed {@code command} int (raw, unvalidated
     * client input; the packet's own no-arg constructor defaults it to -1) used to be
     * fed straight into {@code Command.values()[command]}, throwing
     * {@code ArrayIndexOutOfBoundsException} on the server's main packet-handling
     * thread. Asserts the id-keyed {@link Command#resolveCommand} rejection path
     * handles it gracefully instead.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void commandWithOutOfRangeIdDoesNotCrashServer(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        try {
            new DragonCommandPacket(999).handleServer(null, player);
        } catch (Exception e) {
            helper.fail("An out-of-range command id (999) crashed command processing instead of being rejected"
                    + " gracefully: " + e);
            return;
        }

        helper.succeed();
    }

    /**
     * {@code DragonAI#getWanderTarget} strips the {@code GlobalPos}'s dimension and
     * paths toward the raw {@code BlockPos} unconditionally — arming a WANDER target
     * in the PLAYER's dimension for a dragon findDragon resolved in a DIFFERENT
     * dimension would silently mis-path it once it eventually arrives there. Mirrors
     * {@link #callAcrossDimensions}' changeDimension setup, but never summons the
     * dragon back — it stays in the Nether, and the WANDER command (issued from the
     * player's Overworld connection) must be refused rather than arming a
     * wrong-dimension target.
     *
     * <p>
     * Unlike {@link #callAcrossDimensions}, nothing here ever calls {@code callDragon}
     * — WANDER never mints a chunk ticket of its own — so the destination chunk's
     * entity section would never be promoted "visible" for {@code findDragon} to see,
     * and a {@code succeedWhen} poll would simply time out for a reason unrelated to
     * the guard under test. Uses the same forced-ticket-then-drain recipe as {@link
     * #runRecallRefusesOnCrossLevelRealUuidCollision} to promote the destination chunk
     * synchronously before the dragon ever arrives there.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void wanderCommandIgnoredWhenDragonInDifferentDimension(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
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

        // Force the destination chunk to actually become entity-visible BEFORE the
        // dragon ever arrives there — see runRecallRefusesOnCrossLevelRealUuidCollision's
        // javadoc for why a plain getChunk() alone is not enough.
        ChunkPos destChunkPos = new ChunkPos(0, 0);
        netherDim.getChunkSource().addRegionTicket(TicketType.FORCED, destChunkPos, 0, destChunkPos);
        try {
            netherDim.getChunk(0, 0);
            for (int i = 0; i < 5; i++) {
                netherDim.getChunkSource().tick(() -> true, true);
            }
            while (netherDim.getChunkSource().pollTask()) {
                // Drain until the queue is empty.
            }

            var dimensionTransition = new DimensionTransition(
                    netherDim, new Vec3(0, 0, 0), new Vec3(0, 0, 0), 0, 0, true, DimensionTransition.DO_NOTHING);

            var netherDragon = (TameableDragonEntity) dragon.changeDimension(dimensionTransition);
            if (netherDragon == null) {
                helper.fail("changeDimension returned null when moving the dragon to the Nether");
                return;
            }

            var found = DragonWhistleHandler.findDragon(player, index);
            if (found == null) {
                helper.fail("Dragon not resolvable across dimensions even after forcing the destination chunk"
                        + " visible — cannot distinguish a broken WANDER guard from a resolution miss");
                return;
            }

            // Discard setup packets so only the WANDER command's own feedback (if any)
            // is counted below. This is the discriminator that actually distinguishes
            // "the guard explicitly refused" from "resolution silently failed and the
            // switch was skipped entirely" — both leave hasWanderTarget() false, but
            // only the FIXED code sends dmr.dragon_call.not_found in either case; the
            // pre-fix bare-lookup-miss path sends nothing at all.
            drainSystemChatMessages(player);

            new DragonCommandPacket(Command.WANDER).handleServer(null, player);

            if (found.hasWanderTarget()) {
                helper.fail("WANDER armed a cross-dimension GlobalPos target instead of being refused with"
                        + " feedback");
                return;
            }

            var messages = drainSystemChatMessages(player);
            boolean sawNotFound = messages.stream()
                    .anyMatch(component -> component.getContents() instanceof TranslatableContents contents
                            && "dmr.dragon_call.not_found".equals(contents.getKey()));
            if (!sawNotFound) {
                helper.fail("WANDER did not arm a cross-dimension target, but also sent no"
                        + " dmr.dragon_call.not_found feedback — this cannot distinguish the guard actually"
                        + " firing from the pre-fix bare lookup silently skipping the whole switch (all"
                        + " messages seen: " + messages + ")");
                return;
            }

            helper.succeed();
        } finally {
            netherDim.getChunkSource().removeRegionTicket(TicketType.FORCED, destChunkPos, 0, destChunkPos);
        }
    }

    /**
     * Positive control for {@link #wanderCommandIgnoredWhenDragonInDifferentDimension}:
     * the IDENTICAL command, issued against a dragon resolvable in the player's OWN
     * dimension, must still arm a wander target. Without this, a guard accidentally
     * inverted (e.g. {@code .equals(...)} flipped to {@code !.equals(...)}) would pass
     * the cross-dimension refusal test above and still pass the suite, silently
     * breaking WANDER for the overwhelmingly common same-dimension case.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void wanderCommandArmsTargetWhenDragonInSameDimension(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        new DragonCommandPacket(Command.WANDER).handleServer(null, player);

        if (!dragon.hasWanderTarget()) {
            helper.fail("Same-dimension WANDER did not arm a wander target — a guard inverted to !equals would"
                    + " incorrectly refuse this too, and only this positive control would catch it.");
            return;
        }

        helper.succeed();
    }

    /**
     * Gate-required (release-blocking): proves the per-player findDragon throttle does
     * not swallow a legitimate second command. The cheap same-level
     * {@code level.getEntity(entityId)} fast path is unthrottled — with the binding's
     * stored {@code entityId} valid throughout (the normal case), two FOLLOW presses
     * issued back-to-back must BOTH resolve the dragon and BOTH emit their action-bar
     * feedback. A throttle wrongly applied to the fast path (rather than only to the
     * expensive findDragon fallback on a fast-path miss) would silently swallow the
     * second press — reintroducing exactly the silent no-op summon-02 is about.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void twoConsecutiveFollowPressesBothTakeEffect(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        // Setup packets (placeNewPlayer's initial sync, moveToCentre, tamedFor's own
        // feedback, ...) queue chat packets of their own — drain and discard so only
        // the two FOLLOW presses' own messages are counted below.
        drainSystemChatMessages(player);

        new DragonCommandPacket(Command.FOLLOW).handleServer(null, player);
        new DragonCommandPacket(Command.FOLLOW).handleServer(null, player);

        var messages = drainSystemChatMessages(player);
        var followMessages = messages.stream()
                .filter(component -> component.getContents() instanceof TranslatableContents contents
                        && "dmr.command_mode.follow.text".equals(contents.getKey()))
                .toList();

        if (followMessages.size() != 2) {
            helper.fail("Two consecutive FOLLOW presses (binding's stored entityId valid throughout, so the"
                    + " fast path should resolve both, unthrottled) produced " + followMessages.size()
                    + " dmr.command_mode.follow.text messages instead of 2 — a throttle wrongly applied to the"
                    + " fast path would silently swallow the second press (all messages seen: " + messages + ")");
            return;
        }

        helper.succeed();
    }

    /**
     * Fix-round (command-packet-tests cluster): {@link
     * #twoConsecutiveFollowPressesBothTakeEffect} only ever exercises the UNTHROTTLED
     * fast path (its binding's stored {@code entityId} stays valid throughout, per
     * its own javadoc) — the throttled branch commit 19 introduced had zero coverage
     * anywhere in the suite. This reuses {@link
     * #followCommandResolvesWithStaleEntityIdBinding}'s stale-entityId/correct-
     * dragonUUID binding so the fast path MISSES on every press, forcing both
     * presses through {@code findDragon} and therefore the throttle. The first press
     * (findDragon not yet throttled) must resolve the dragon and emit exactly one
     * {@code dmr.command_mode.follow.text}; the second, issued back-to-back with no
     * tick advance (the 10-tick throttle window cannot have elapsed), must be
     * suppressed — and report that suppression honestly via the documented {@code
     * dmr.dragon_call.on_cooldown} signal, never the misleading {@code
     * dmr.dragon_call.not_found} (the dragon resolved successfully moments earlier),
     * and must not further mutate the dragon's sit/wander state beyond what the
     * first press already did. Mock players get a fresh {@code UUID.randomUUID()}
     * per test (disassembled {@code ExtendedGameTestHelper
     * #makeTickingMockServerPlayerInLevel}), so the static throttle map cannot leak
     * state across concurrently-batched tests — this test is deterministic.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void secondFollowPressWithinThrottleWindowGetsCooldownSignalNotNotFound(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        var index = 0;
        DragonWhistleHandler.setDragon(player, dragon, index);
        dragon.setOrderedToSit(true);

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        cap.setPlayerInstance(player);

        // Same corruption as followCommandResolvesWithStaleEntityIdBinding: correct
        // dragonUUID, random non-matching entityId — forces every press to miss the
        // fast path and fall through to findDragon (and therefore the throttle),
        // instead of twoConsecutiveFollowPressesBothTakeEffect's unthrottled shape.
        cap.setDragonInstance(index, new DragonInstance(player.level, UUID.randomUUID(), dragon.getDragonUUID()));

        for (var whistle : ModItems.DRAGON_WHISTLES.values()) {
            if (((DragonWhistleItem) whistle.get()).getColor().getId() == index) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle.get()));
                break;
            }
        }

        drainSystemChatMessages(player);

        new DragonCommandPacket(Command.FOLLOW).handleServer(null, player);

        if (dragon.isOrderedToSit()) {
            helper.fail("First FOLLOW press did not resolve the dragon through the stale-entityId binding — the"
                    + " dragon is still sitting, so this test cannot validate the throttle on the second press.");
            return;
        }

        var firstMessages = drainSystemChatMessages(player);
        var firstFollowCount = firstMessages.stream()
                .filter(component -> component.getContents() instanceof TranslatableContents contents
                        && "dmr.command_mode.follow.text".equals(contents.getKey()))
                .count();
        if (firstFollowCount != 1) {
            helper.fail("First FOLLOW press (through the stale-entityId fallback, unthrottled) produced "
                    + firstFollowCount + " dmr.command_mode.follow.text messages instead of 1 (all messages seen: "
                    + firstMessages + ")");
            return;
        }

        // Back-to-back with no tick advance: level.getGameTime() cannot have moved, so
        // the 10-tick throttle window is still open from the first press's stamp.
        new DragonCommandPacket(Command.FOLLOW).handleServer(null, player);

        if (dragon.isOrderedToSit() || dragon.hasWanderTarget()) {
            helper.fail("Throttle-suppressed second FOLLOW press mutated dragon state beyond what the first"
                    + " press already did — a suppressed press must be a true no-op.");
            return;
        }

        var secondMessages = drainSystemChatMessages(player);
        var notFoundCount = secondMessages.stream()
                .filter(component -> component.getContents() instanceof TranslatableContents contents
                        && "dmr.dragon_call.not_found".equals(contents.getKey()))
                .count();
        var onCooldownCount = secondMessages.stream()
                .filter(component -> component.getContents() instanceof TranslatableContents contents
                        && "dmr.dragon_call.on_cooldown".equals(contents.getKey()))
                .count();

        if (notFoundCount > 0) {
            helper.fail("Throttle-suppressed second FOLLOW press emitted dmr.dragon_call.not_found — a false"
                    + " statement, since the dragon resolved successfully on the first press moments earlier"
                    + " (all messages seen: " + secondMessages + ")");
            return;
        }

        if (onCooldownCount != 1) {
            helper.fail("Throttle-suppressed second FOLLOW press did not emit the documented"
                    + " dmr.dragon_call.on_cooldown signal exactly once (all messages seen: " + secondMessages
                    + ")");
            return;
        }

        helper.succeed();
    }
}
