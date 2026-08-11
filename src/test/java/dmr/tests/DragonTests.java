package dmr.tests;

import dmr.DMRTestConstants;
import dmr.DragonMounts.network.packets.DismountDragonPacket;
import dmr.DragonMounts.registry.DragonArmorRegistry;
import dmr.DragonMounts.registry.DragonBreedsRegistry;
import dmr.DragonMounts.registry.ModEntities;
import dmr.DragonMounts.registry.ModItems;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.items.DragonArmorItem;
import dmr.DragonMounts.types.dragonBreeds.DragonBreed;
import dmr.DragonMounts.util.PlayerStateUtils;
import java.lang.reflect.Field;
import java.util.Objects;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/**
 * Tests for the dragon entity functionality of the mod. These tests verify that
 * dragons can be properly tamed, equipped, ridden, and that they behave
 * correctly in various situations.
 */
@PrefixGameTestTemplate(false)
@ForEachTest(groups = "Dragons")
public class DragonTests {

    /**
     * Tests the dragon taming functionality.
     *
     * <p>
     * This test verifies that: 1. A player can tame a dragon by interacting with it
     * while holding a tropical fish 2. After interaction, the dragon's tame status
     * is set to true
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void tameDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        helper.succeedWhen(() -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TROPICAL_FISH, 64));
            dragon.interact(player, InteractionHand.MAIN_HAND);

            if (!dragon.isTame()) {
                helper.fail("Dragon was not tamed");
            }
        });
    }

    /**
     * Tests the dragon tamedFor method.
     *
     * <p>
     * This test verifies that: 1. A dragon can be directly tamed for a specific
     * player using the tamedFor method 2. After taming, the dragon's tame status is
     * set to true 3. The dragon's owner is correctly set to the player
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void tamedFor(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        if (!dragon.isTame()) {
            helper.fail("Dragon was not tamed");
        }

        if (dragon.getOwner() != null && !Objects.equals(dragon.getOwner().getId(), player.getId())) {
            helper.fail("Dragon owner is not the player");
        }

        helper.succeed();
    }

    /**
     * Tests that a tamed dragon will look at its owner.
     *
     * <p>
     * This test verifies that: 1. A tamed dragon will set its look target to its
     * owner 2. The dragon's look target position is close to the player's position
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void willLookAtPlayer(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);
        helper.succeedWhen(
                () -> dragon.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).ifPresent(lookTarget -> {
                    if (lookTarget.currentPosition().distanceTo(player.position()) > 0.5) {
                        helper.fail("Dragon did not look at player");
                    }
                }));
    }

    /**
     * Tests that a tamed dragon will attack its owner's target.
     *
     * <p>
     * This test verifies that:
     * 1. When a player attacks another entity
     * 2. The player's tamed dragon will target the same entity
     * 3. The dragon's target is correctly set to the player's target
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void willAttackOwnerTarget(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var target = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        target.moveToCorner();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        helper.onEachTick(() -> {
            player.tick();
            target.tick();
            dragon.tick();
        });

        dragon.tamedFor(player, true);
        helper.runAtTickTime(20, () -> {
            player.attack(target);
            player.doHurtTarget(target);
        });

        helper.succeedWhen(() -> {
            var dragonTarget = dragon.getTarget();
            if (dragonTarget == null || (!dragonTarget.is(target) && dragonTarget.getId() != target.getId())) {
                helper.fail("Dragon did not attack owner's target. Target was: " + dragonTarget);
            }
        });
    }
    //
    //        /**
    //         * Tests that a tamed dragon will defend its owner.
    //         *
    //         * <p>
    //         * This test verifies that:
    //         * 1. When a player is attacked by another entity
    //         * 2. The player's tamed dragon will target the attacker
    //         * 3. The dragon's target is correctly set to the entity that attacked its owner
    //         *
    //         * @param helper The game test helper
    //         */
    //        @EmptyTemplate(floor = true)
    //        @GameTest
    //        @TestHolder
    //        public static void willDefendOwner(ExtendedGameTestHelper helper) {
    //            var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
    //            player.moveToCentre();
    //
    //            var target = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
    //            target.moveToCorner();
    //
    //            var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
    //            dragon.setBreed(DragonBreedsRegistry.getDefault());
    //
    //            dragon.tamedFor(player, true);
    //            target.attack(player);
    //
    //            helper.onEachTick(() -> {
    //                player.tick();
    //                target.tick();
    //                dragon.tick();
    //            });
    //
    //            helper.succeedWhen(() -> {
    //                var dragonTarget = dragon.getTarget();
    //                if(dragonTarget == null || (!dragonTarget.is(target) && dragonTarget.getId() != target.getId()))
    // {
    //                    helper.fail("Dragon did not defend owner. Target was: " + dragonTarget);
    //                }
    //            });
    //        }
    //
    //        /**
    //         * Tests that a tamed dragon will attack a non-owner entity when directed.
    //         *
    //         * <p>
    //         * This test verifies that:
    //         * 1. When a player attacks another entity
    //         * 2. The player's tamed dragon will target that entity
    //         * 3. The dragon's target is correctly set to the entity the player attacked
    //         *
    //         * @param helper The game test helper
    //         */
    //        @EmptyTemplate(floor = true)
    //        @GameTest
    //        @TestHolder
    //        public static void willAttackNonOwner(ExtendedGameTestHelper helper) {
    //            var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
    //            player.moveToCentre();
    //
    //            var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
    //            dragon.setBreed(DragonBreedsRegistry.getDefault());
    //
    //            var otherPlayer = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
    //
    //            dragon.tamedFor(player, true);
    //            player.attack(otherPlayer);
    //
    //            helper.onEachTick(() -> {
    //                player.tick();
    //                otherPlayer.tick();
    //                dragon.tick();
    //            });
    //
    //            helper.succeedWhen(() -> {
    //                var target = dragon.getTarget();
    //                if(target == null || (!target.is(otherPlayer) && target.getId() != otherPlayer.getId())) {
    //                    helper.fail("Dragon did not attack non-owner. Target was: " + target);
    //                }
    //            });
    //        }

    /**
     * Tests that a tamed dragon will not attack its owner.
     *
     * <p>
     * This test verifies that:
     * 1. When a player attacks their tamed dragon
     * 2. The dragon will not retaliate or set the player as its target
     * 3. The dragon's target remains null or is not set to the player
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void willNotAttackOwner(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        dragon.tamedFor(player, true);
        player.attack(dragon);

        player.tick();
        dragon.tick();

        var target = dragon.getTarget();
        if (target != null && (target.is(player) || target.getId() == player.getId())) {
            helper.fail("Dragon attacked owner");
        }

        helper.succeed();
    }

    /**
     * Tests that a tamed dragon will not attack another dragon tamed by the same player.
     *
     * <p>
     * This test verifies that:
     * 1. When a player has multiple tamed dragons
     * 2. If the player attacks one of their tamed dragons
     * 3. The other tamed dragons will not target the attacked dragon
     *
     * @param helper The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void willNotAttackTamed(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();

        var dragon1 = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        var dragon2 = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon1.setBreed(DragonBreedsRegistry.getDefault());
        dragon2.setBreed(DragonBreedsRegistry.getDefault());

        dragon1.tamedFor(player, true);
        dragon2.tamedFor(player, true);

        player.attack(dragon2);

        helper.onEachTick(() -> {
            player.tick();
            dragon1.tick();
            dragon2.tick();
        });

        helper.succeedWhen(() -> {
            var target = dragon1.getTarget();

            if (target != null && (target.is(dragon2) || target.getId() == dragon2.getId())) {
                helper.fail("Dragon attacked tamed dragon. Target was: " + target);
            }
        });
    }

    /**
     * Tests that a dragon can be saddled.
     *
     * <p>
     * This test verifies that: 1. A tamed dragon can be equipped with a saddle 2.
     * After equipping, the dragon's saddled status is set to true
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void saddleDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        dragon.tamedFor(player, true);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SADDLE));
        dragon.interact(player, InteractionHand.MAIN_HAND);

        if (!dragon.isSaddled()) {
            helper.fail("Dragon was not saddled");
        }

        helper.succeed();
    }

    /**
     * Tests that a dragon can be equipped with armor.
     *
     * <p>
     * This test verifies that: 1. A tamed dragon can be equipped with dragon armor
     * 2. After equipping, the dragon's armor status is set to true
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void armorDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        dragon.tamedFor(player, true);

        player.setItemInHand(
                InteractionHand.MAIN_HAND, DragonArmorItem.getArmorStack(DragonArmorRegistry.getDragonArmor("iron")));
        dragon.interact(player, InteractionHand.MAIN_HAND);

        if (!dragon.isWearingArmor()) {
            helper.fail("Dragon was not armored");
        }

        helper.succeed();
    }

    /**
     * Tests that a dragon can be equipped with a chest.
     *
     * <p>
     * This test verifies that: 1. A tamed dragon can be equipped with a chest 2.
     * After equipping, the dragon's chest status is set to true
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void giveChestToDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        dragon.tamedFor(player, true);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
        dragon.interact(player, InteractionHand.MAIN_HAND);

        if (!dragon.hasChest()) {
            helper.fail("Dragon did not get chest");
        }

        helper.succeed();
    }

    /**
     * Tests that a dragon's inventory is not dropped when the dragon is selected
     * with a whistle.
     *
     * <p>
     * This test verifies that: 1. When a dragon with a chest and items is killed 2.
     * If the dragon is selected with a whistle, its inventory items are not dropped
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void dontDropWhenSelected(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        dragon.setChest(true);
        dragon.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 64));
        PlayerStateUtils.getHandler(player).setDragonToWhistle(dragon, 1);

        dragon.kill();

        helper.succeedWhen(
                () -> helper.assertItemEntityNotPresent(Items.DIAMOND, helper.relativePos(dragon.blockPosition()), 3));
    }

    /**
     * Tests that a dragon's chest contents are dropped when the dragon is killed.
     *
     * <p>
     * This test verifies that: 1. When a dragon with a chest containing items is
     * killed 2. The items in the chest are dropped in the world 3. The correct
     * number of items are dropped
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void dropChestContentsOnDeath(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        dragon.tamedFor(player, true);
        dragon.setChest(true);
        dragon.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 64));

        dragon.kill();

        helper.succeedWhen(
                () -> helper.assertItemEntityCountIs(Items.DIAMOND, helper.relativePos(dragon.blockPosition()), 3, 64));
    }

    /**
     * Tests that a dragon's saddle is dropped when the dragon is killed.
     *
     * <p>
     * This test verifies that: 1. When a saddled dragon is killed 2. The saddle is
     * dropped in the world 3. Exactly one saddle is dropped
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void dropSaddleOnDeath(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        dragon.tamedFor(player, true);
        dragon.equipSaddle(new ItemStack(Items.SADDLE), SoundSource.MASTER);

        dragon.kill();

        helper.succeedWhen(
                () -> helper.assertItemEntityCountIs(Items.SADDLE, helper.relativePos(dragon.blockPosition()), 3, 1));
    }

    /**
     * Tests that a dragon's armor is dropped when the dragon is killed.
     *
     * <p>
     * This test verifies that: 1. When an armored dragon is killed 2. The dragon
     * armor is dropped in the world 3. Exactly one armor item is dropped
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void dropArmorOnDeath(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        dragon.tamedFor(player, true);
        dragon.equipArmor(player, DragonArmorItem.getArmorStack(DragonArmorRegistry.getDragonArmor("iron")));

        dragon.kill();

        helper.succeedWhen(() -> helper.assertItemEntityCountIs(
                ModItems.DRAGON_ARMOR.get(), helper.relativePos(dragon.blockPosition()), 3, 1));
    }

    /**
     * Tests that a player can ride a saddled dragon.
     *
     * <p>
     * This test verifies that: 1. A player can saddle a tamed dragon 2. After
     * saddling, the player can interact with the dragon to mount it 3. The player
     * becomes a passenger of the dragon
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void rideDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        dragon.tamedFor(player, true);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SADDLE));
        dragon.interact(player, InteractionHand.MAIN_HAND);

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        dragon.interact(player, InteractionHand.MAIN_HAND);

        if (!player.isPassenger()) {
            helper.fail("Player is not riding dragon");
        }

        helper.succeed();
    }

    /**
     * Tests that a player can dismount from a dragon.
     *
     * <p>
     * This test verifies that: 1. A player can mount a saddled dragon 2. When the
     * shouldDismount flag is set, the player dismounts from the dragon 3. After
     * dismounting, the player is no longer a passenger
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void dismountDragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        dragon.tamedFor(player, true);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SADDLE));
        dragon.interact(player, InteractionHand.MAIN_HAND);

        PlayerStateUtils.getHandler(player).shouldDismount = true;

        helper.succeedWhen(() -> {
            if (player.isPassenger()) {
                helper.fail("Player is still riding dragon");
            }
        });
    }

    /**
     * W8-SYNC-2 regression: {@code DismountDragonPacket#handle} must only write
     * {@code cap.shouldDismount} when the player is ACTUALLY controlling a dragon.
     * Simulates the race the fix closes: a server-side dismount happens through some
     * OTHER path (dragon death, a third-party mod, etc. — modelled here with a plain
     * {@code player.stopRiding()}), and then a late/stray
     * {@code DismountDragonPacket(playerId, true)} — sent by the client before that
     * happened, but arriving after — reaches {@code handle()}. Pre-fix, the
     * unconditional write left {@code cap.shouldDismount} stuck {@code true} forever
     * (it is serialized into the player attachment), which vanilla's own mount check
     * uses to bounce every later mount attempt.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void dismountFlagNotSetForPlayerNoLongerControllingADragon(ExtendedGameTestHelper helper) {
        var player = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        player.moveToCentre();
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(player, true);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SADDLE));
        dragon.interact(player, InteractionHand.MAIN_HAND);

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        dragon.interact(player, InteractionHand.MAIN_HAND);

        if (!player.isPassenger()) {
            helper.fail("Setup failed: player never mounted the dragon");
            return;
        }

        // Server-side dismount through a path OTHER than DismountDragonPacket — this
        // itself already correctly leaves cap.shouldDismount false via
        // EntityDismountMixin's stopRiding() inject.
        player.stopRiding();

        // The late/stray packet: player is no longer controlling any dragon.
        var packet = new DismountDragonPacket(player.getId(), true);
        packet.handle(null, player);

        if (PlayerStateUtils.getHandler(player).shouldDismount) {
            helper.fail("shouldDismount was set true by a DismountDragonPacket arriving after the"
                    + " player already stopped controlling a dragon — this permanently bounces the"
                    + " next mount attempt (W8-SYNC-2)");
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-3 regression: {@code TameableDragonEntity#onSyncedDataUpdated} now
     * calls {@code super} unconditionally BEFORE branching on {@code DATA_FLAGS_ID}
     * (previously super was skipped entirely for that one accessor). This guards
     * that the sit-pose dimension refresh the method has always performed directly
     * still fires after the reorder.
     *
     * <p>
     * No red-proof recorded for this test: per design-sync.json's compat audit
     * (W8-Sync-3), no class in the super chain (Entity/LivingEntity/AgeableMob/
     * DragonBreedComponent) special-cases DATA_FLAGS_ID, so this accessor's
     * dimension refresh was never actually broken by the swallowed super call — see
     * .fork-notes/wave8/red-baseline.md for the full carve-out rationale.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void sitPoseStillRefreshesDimensionsAfterSuperReorder(ExtendedGameTestHelper helper) {
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        var standingHeight = dragon.getBbHeight();

        dragon.setOrderedToSit(true);

        var sittingHeight = dragon.getBbHeight();
        var expectedSittingHeight = 2.15f * dragon.getScale();

        if (Math.abs(sittingHeight - expectedSittingHeight) > 0.001f) {
            helper.fail("Dragon bounding-box height did not refresh to the sitting pose after"
                    + " setOrderedToSit(true): expected " + expectedSittingHeight + " but was " + sittingHeight);
        }

        if (Math.abs(sittingHeight - standingHeight) < 0.001f) {
            helper.fail("Dragon bounding-box height did not change between standing (" + standingHeight
                    + ") and sitting (" + sittingHeight + ") — refreshDimensions() likely did not run");
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-3 regression: proves the reorder does not disturb the ALREADY-working
     * forwarding for accessors other than {@code DATA_FLAGS_ID} — specifically
     * {@code breedDataAccessor}, which {@code DragonBreedComponent}'s own
     * {@code onSyncedDataUpdated} override reacts to by calling
     * {@code updateAgeProperties()} (which resets the {@code STEP_HEIGHT} attribute
     * to a computed value). {@code setBreed()} itself never calls
     * {@code updateAgeProperties()} directly — only the accessor-update callback
     * does — so deliberately corrupting {@code STEP_HEIGHT} and then observing it
     * get reset by a breed change proves the callback chain (TameableDragonEntity ->
     * ... -> DragonBreedComponent) actually ran end to end.
     *
     * <p>
     * No red-proof recorded for this test: a non-{@code DATA_FLAGS_ID} accessor
     * already called super in the pre-fix else-branch, so this path is unaffected by
     * the reorder either way — see .fork-notes/wave8/red-baseline.md.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void breedAccessorUpdateStillReachesDragonBreedComponentAfterSuperReorder(
            ExtendedGameTestHelper helper) {
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        DragonBreed testBreed = new DragonBreed();
        testBreed.setId("sync3_breed_accessor_test");
        DragonBreedsRegistry.register(testBreed);

        // Deliberately corrupt an attribute that ONLY updateAgeProperties() (called
        // from inside DragonBreedComponent#onSyncedDataUpdated, in reaction to a
        // breedDataAccessor change) ever recomputes.
        var stepHeight = dragon.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight == null) {
            helper.fail("Dragon has no STEP_HEIGHT attribute instance");
            return;
        }
        stepHeight.setBaseValue(9.0);

        dragon.setBreed(testBreed);

        if (stepHeight.getBaseValue() == 9.0) {
            helper.fail("STEP_HEIGHT was not recomputed after a breed change — "
                    + "DragonBreedComponent#onSyncedDataUpdated did not run (super forwarding broken by the reorder)");
        }

        helper.succeed();
    }

    /**
     * Sets a {@link DragonBreed}'s private {@code sizeModifier} field via reflection (no
     * public setter exists — same pattern used by {@code BreedingUtilsTests} for
     * {@code habitats}), registers it, and returns it for use in a test. Reflection
     * failures are reported via {@code helper.fail} rather than a checked exception, to
     * match this file's (and BreedingUtilsTests') existing convention for {@code @GameTest}
     * methods.
     */
    private static DragonBreed makeSizedTestBreed(ExtendedGameTestHelper helper, String id, float sizeModifier) {
        DragonBreed breed = new DragonBreed();
        breed.setId(id);
        try {
            Field sizeModifierField = DragonBreed.class.getDeclaredField("sizeModifier");
            sizeModifierField.setAccessible(true);
            sizeModifierField.set(breed, sizeModifier);
        } catch (Exception e) {
            helper.fail("Failed to set sizeModifier for " + id + ": " + e.getMessage());
        }
        DragonBreedsRegistry.register(breed);
        return breed;
    }

    /**
     * Returns the additive culling margin {@code getBoundingBoxForCulling()} layers on top
     * of the dragon's own hitbox, isolated from the hitbox's own scale-dependent growth
     * (via {@code getDimensions}) by diffing against {@code getBoundingBox()} directly
     * rather than comparing raw culling-box sizes.
     */
    private static double cullingMargin(TameableDragonEntity dragon) {
        return dragon.getBoundingBoxForCulling().maxX - dragon.getBoundingBox().maxX;
    }

    /**
     * W8-SYNC-6 regression: proves the culling margin now scales with the dragon's model
     * size instead of staying pinned at a flat +5, while staying byte-identical to today
     * for a default-scale (size modifier 1.0) adult. Isolates the margin itself (see
     * {@link #cullingMargin}) rather than comparing raw culling-box dimensions, since
     * {@code getBoundingBox()} already scales with size and conflating the two would prove
     * nothing about the additive padding specifically.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void testCullingBoxScalesWithDragonSize(ExtendedGameTestHelper helper) {
        var defaultScaleDragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        defaultScaleDragon.setBreed(makeSizedTestBreed(helper, "sync6_scale1_breed", 1.0f));
        defaultScaleDragon.setBaby(false);

        // Read synchronously (no tick elapses between spawn and the getBoundingBox* calls
        // below), so both dragons can share the same spawn position without collision
        // resolution ever running — no need for a larger-than-default template.
        var oversizedDragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        oversizedDragon.setBreed(makeSizedTestBreed(helper, "sync6_scale2_breed", 2.0f));
        oversizedDragon.setBaby(false);

        var defaultMargin = cullingMargin(defaultScaleDragon);
        var oversizedMargin = cullingMargin(oversizedDragon);

        if (defaultMargin != 5.0) {
            helper.fail("Default-scale (1.0) adult dragon's culling margin changed from today's flat +5 — expected"
                    + " 5.0, got " + defaultMargin);
        }

        if (oversizedMargin <= defaultMargin) {
            helper.fail("Size-modifier-2.0 dragon's culling margin (" + oversizedMargin
                    + ") is not strictly larger than the default-scale margin (" + defaultMargin
                    + ") — the culling box is not scaling with dragon size");
        }

        helper.succeed();
    }

    /**
     * W8-SYNC-6 regression: proves the {@code Math.max(1.0, ...)} floor keeps a baby
     * dragon's culling margin byte-identical to today's flat +5, rather than shrinking it
     * proportionally to the baby's 0.5x scale factor.
     *
     * @param helper
     *               The game test helper
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void testCullingBoxFlooredForBabies(ExtendedGameTestHelper helper) {
        var babyDragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        babyDragon.setBreed(makeSizedTestBreed(helper, "sync6_baby_breed", 1.0f));
        babyDragon.setBaby(true);

        var margin = cullingMargin(babyDragon);

        if (margin != 5.0) {
            helper.fail("Baby dragon's culling margin was not floored to today's flat +5 — got " + margin);
        }

        helper.succeed();
    }
}
