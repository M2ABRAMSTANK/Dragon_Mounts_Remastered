package dmr.tests;

import dmr.DMRTestConstants;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.registry.DragonBreedsRegistry;
import dmr.DragonMounts.registry.ModEntities;
import dmr.DragonMounts.server.ai.teams.DragonAllyService;
import dmr.DragonMounts.server.ai.teams.TeamProvider;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/**
 * Gametest coverage for the dragon-passivity-toward-teammates feature ({@code
 * W8-TEAMS-1/2/3}). Split from {@link DragonAllyServiceTests} because these tests need
 * real {@code Player}/{@code TameableDragonEntity} objects (see that class's javadoc for
 * why the pure core lives in plain JUnit and this doesn't).
 *
 * <p>
 * Uses {@code helper.fail}/{@code helper.succeed} exclusively rather than JUnit's {@code
 * Assertions}, matching every other {@code @GameTest}-annotated class in this repo:
 * {@code runGameTestServer}'s runtime classpath does not carry {@code junit-jupiter-api},
 * so a {@code @GameTest} method that calls {@code org.junit.jupiter.api.Assertions.*}
 * fails every time with an opaque {@code NoClassDefFoundError} (learned the hard way —
 * the first version of this file did exactly that; see this class's commit history).
 *
 * <p>
 * <b>Filed one commit early (commit 21, not 22/23 as the wave plan's {@code
 * commitPlan} file lists suggest):</b> {@code W8-TEAMS-1-C4PARITY} — the artifact that
 * discharges C4 for this whole area — needs real {@code Player} objects to compare
 * against real {@code isAlliedTo()} calls, and {@code DragonAllyServiceTests.java} (the
 * only test file commit 21's plan entry lists) cannot host it per that class's own
 * javadoc. This file is created here instead, and commits 22/23 extend it with their own
 * test methods exactly as the plan already intends.
 *
 * <p>
 * Per {@code gate-design-teams.json}'s constraint violation on the original test
 * strategy: a tamed dragon inherits its owner's vanilla scoreboard team ({@code
 * TamableAnimal#getTeam}), so a vanilla {@code PlayerTeam} can never stand in for FTB/OPAC
 * in a "teammate is spared" test — every such test here uses an injected stub {@link
 * TeamProvider} on players with NO vanilla team instead.
 */
@PrefixGameTestTemplate(false)
@ForEachTest(groups = "Team Passivity")
public class DragonTeamPassivityTests {

    /**
     * The default {@code @EmptyTemplate} size is a mere {@code 3x3x3} (confirmed via the
     * test framework's own {@code AnnotationDefault}), and {@code DMRTestConstants
     * .TEST_POS} sits near its corner — plenty for tests that stay within DMR's existing
     * sensor-override/whistle mechanisms (which need no real geometry at all), but too
     * small for anything exercising real {@code Sensor.isEntityAttackable}/{@code
     * TargetGoal} line-of-sight and range checks against a Player a few blocks away.
     * Mirrors {@code PathNavigationTests.SMALL_TEMPLATE}/{@code DragonWhistleTests
     * .LARGE_TEMPLATE}'s own pattern for the same reason.
     */
    private static final String ROOMY_TEMPLATE = "15x5x15";

    private static TeamProvider stubTeammatesOf(UUID a, UUID b) {
        return new TeamProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean areTeammates(MinecraftServer server, UUID x, UUID y) {
                return (x.equals(a) && y.equals(b)) || (x.equals(b) && y.equals(a));
            }
        };
    }

    /**
     * {@code W8-TEAMS-1-C4PARITY}: with the provider list empty AND {@code
     * DRAGON_TEAM_PASSIVITY} false, {@code DragonAllyService.isAllied(a, b)} must equal
     * {@code a.isAlliedTo(b)} for every operand order actually used at the 4 catalogued
     * {@code isAlliedTo} call sites — this is the artifact that discharges C4 for the
     * whole teams area. Covers both degrading conditions independently (either one alone
     * must be enough to fall all the way back to vanilla), per {@code
     * gate-design-teams.json}'s requirement on T3-S1.
     *
     * <ul>
     * <li>{@code DragonAttackablesSensor}: {@code s.isAlliedTo(dragon)}, {@code
     * s.isAlliedTo(dragon.getOwner())}
     * <li>{@code DragonBreathComponent#canHarmWithBreath}: {@code
     * target.isAlliedTo(getOwner())} — same shape as the second sensor clause
     * <li>{@code DragonAttackPacket}: {@code s.isAlliedTo(player)} where {@code player} is
     * always the owner — same shape again
     * </ul>
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void allyServiceMatchesVanillaIsAlliedToWhenProvidersEmptyOrPassivityOff(
            ExtendedGameTestHelper helper) {
        var owner = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        owner.moveToCentre();
        var candidate = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        candidate.moveToCorner();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(owner, true);

        var previousProviders = DragonAllyService.providers;
        boolean previousPassivity = ServerConfig.DRAGON_TEAM_PASSIVITY;
        try {
            // Condition 1: providers empty, passivity ON — the empty list alone must
            // degrade the mod path to false.
            DragonAllyService.setProvidersForTest(List.of());
            ServerConfig.DRAGON_TEAM_PASSIVITY = true;

            if (candidate.isAlliedTo(dragon) != DragonAllyService.isAllied(candidate, dragon)) {
                helper.fail("isAllied(candidate, dragon) must match vanilla isAlliedTo when providers are empty");
                return;
            }
            if (candidate.isAlliedTo(owner) != DragonAllyService.isAllied(candidate, owner)) {
                helper.fail("isAllied(candidate, owner) must match vanilla isAlliedTo when providers are empty");
                return;
            }
            if (owner.isAlliedTo(candidate) != DragonAllyService.isAllied(owner, candidate)) {
                helper.fail("isAllied(owner, candidate) [reversed operand order] must match vanilla isAlliedTo");
                return;
            }

            // Condition 2: passivity OFF, providers stubbed to unconditionally report
            // "teamed" — the config flag alone must degrade the mod path to false,
            // regardless of what any provider would otherwise say.
            DragonAllyService.setProvidersForTest(List.of(stubTeammatesOf(owner.getUUID(), candidate.getUUID())));
            ServerConfig.DRAGON_TEAM_PASSIVITY = false;

            if (candidate.isAlliedTo(dragon) != DragonAllyService.isAllied(candidate, dragon)) {
                helper.fail("isAllied(candidate, dragon) must match vanilla isAlliedTo when passivity is off, even"
                        + " with a stub provider reporting them teamed");
                return;
            }
            if (candidate.isAlliedTo(owner) != DragonAllyService.isAllied(candidate, owner)) {
                helper.fail("isAllied(candidate, owner) must match vanilla isAlliedTo when passivity is off, even"
                        + " with a stub provider reporting them teamed");
                return;
            }
        } finally {
            DragonAllyService.setProvidersForTest(previousProviders);
            ServerConfig.DRAGON_TEAM_PASSIVITY = previousPassivity;
        }

        helper.succeed();
    }

    /**
     * Positive control for the parity test above: proves the mod path is actually LIVE
     * (not merely inert/always-false), by flipping DRAGON_TEAM_PASSIVITY back on with the
     * same stub provider and confirming {@code isAllied} diverges from vanilla {@code
     * isAlliedTo} — otherwise the parity test could pass vacuously because the mod path
     * never fires at all. Uses two players with NO vanilla team, per {@code
     * gate-design-teams.json}'s constraint violation on the original test strategy.
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void allyServiceDivergesFromVanillaOnceAStubProviderReportsTeammatesWithPassivityOn(
            ExtendedGameTestHelper helper) {
        var owner = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        owner.moveToCentre();
        var candidate = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        candidate.moveToCorner();

        if (candidate.isAlliedTo(owner)) {
            helper.fail("Precondition violated: two freshly-spawned players with no shared vanilla team must"
                    + " not read as allied — otherwise this positive control proves nothing");
            return;
        }

        var previousProviders = DragonAllyService.providers;
        boolean previousPassivity = ServerConfig.DRAGON_TEAM_PASSIVITY;
        try {
            DragonAllyService.setProvidersForTest(List.of(stubTeammatesOf(owner.getUUID(), candidate.getUUID())));
            ServerConfig.DRAGON_TEAM_PASSIVITY = true;

            if (!DragonAllyService.isAllied(candidate, owner)) {
                helper.fail("isAllied must report true once a stub provider reports the pair teamed and passivity"
                        + " is on — otherwise the parity test's providers-empty/passivity-off cases prove nothing");
                return;
            }
        } finally {
            DragonAllyService.setProvidersForTest(previousProviders);
            ServerConfig.DRAGON_TEAM_PASSIVITY = previousPassivity;
        }

        helper.succeed();
    }

    /**
     * {@code T3-S1} required change: a teammate's tamed dragon must be allied via the new
     * path too, mirroring vanilla {@code TamableAnimal#isAlliedTo}'s own owner delegation
     * — closing {@code investigate-compat.json constraint-e2}'s explicitly stated "the
     * dragon will attack a teammate's summoned dragon" symptom.
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void petsAreAlliedViaOwnerDelegation(ExtendedGameTestHelper helper) {
        var ownerA = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        ownerA.moveToCentre();
        var ownerB = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        ownerB.moveToCorner();

        var dragonA = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragonA.setBreed(DragonBreedsRegistry.getDefault());
        dragonA.tamedFor(ownerA, true);

        var dragonB =
                helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS.offset(new BlockPos(4, 0, 4)));
        dragonB.setBreed(DragonBreedsRegistry.getDefault());
        dragonB.tamedFor(ownerB, true);

        var previousProviders = DragonAllyService.providers;
        boolean previousPassivity = ServerConfig.DRAGON_TEAM_PASSIVITY;
        try {
            // Regression guard first: with no team relationship at all, two strangers'
            // tamed dragons must NOT read as allied.
            DragonAllyService.setProvidersForTest(List.of());
            ServerConfig.DRAGON_TEAM_PASSIVITY = true;
            if (DragonAllyService.isAllied(dragonA, dragonB)) {
                helper.fail("Two unrelated tamed dragons read as allied with no team relationship at all");
                return;
            }

            // Now stub the OWNERS as teammates and confirm the PETS inherit that via
            // owner delegation.
            DragonAllyService.setProvidersForTest(List.of(stubTeammatesOf(ownerA.getUUID(), ownerB.getUUID())));

            if (!DragonAllyService.isAllied(dragonA, dragonB)) {
                helper.fail("a teammate's tamed dragon must be allied via owner delegation, mirroring vanilla"
                        + " TamableAnimal#isAlliedTo");
                return;
            }
            if (!DragonAllyService.isTeammateOfOwner(dragonA, dragonB)) {
                helper.fail("isTeammateOfOwner must also recognize the other owner's tamed dragon as a teammate");
                return;
            }
        } finally {
            DragonAllyService.setProvidersForTest(previousProviders);
            ServerConfig.DRAGON_TEAM_PASSIVITY = previousPassivity;
        }

        helper.succeed();
    }

    /**
     * {@code W8-TEAMS-2}/{@code T3-S3}: {@code DragonAI#maybeRetaliate}'s synchronous,
     * same-tick {@code hurt()}-time reaction must never fire against a teammate, and must
     * still fire normally against a genuine (non-teamed) attacker — the positive control
     * that proves the gate is narrowly scoped, not a blanket "never retaliate" regression.
     * Uses two players with NO vanilla team (a tamed dragon inherits the owner's team, so
     * a vanilla team would make this vacuous — {@code gate-design-teams.json}'s finding on
     * the original T3-S3 test).
     */
    @EmptyTemplate(value = ROOMY_TEMPLATE, floor = true)
    @GameTest
    @TestHolder
    public static void retaliateSynchronousPathIgnoresTeammateHitButAssistsGenuineAttacker(
            ExtendedGameTestHelper helper) {
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        // Explicit small, ALL-POSITIVE offsets from the dragon (not moveToCentre/
        // moveToCorner, whose absolute distance from the dragon's spawn point is
        // unspecified — matches the proven `player.moveTo(dragon.getX() + 6, ...)`
        // pattern already used elsewhere in this repo). Positive-only matters here: the
        // default @EmptyTemplate size is a mere 3x3x3 (confirmed via the test framework's
        // own AnnotationDefault) and DMRTestConstants.TEST_POS=(1,2,1) sits near ITS
        // corner, so a negative offset (as an earlier version of this test used) walks
        // straight through the template's outer wall — the actual root cause of this
        // test's first failed run: Sensor.isEntityAttackable's line-of-sight raycast was
        // correctly reporting a REAL wall in the way, not a bug in the teammate gate. See
        // {@link #ROOMY_TEMPLATE} for the larger room this and its two sibling tests use
        // instead, to keep this offset scheme working with margin to spare.
        var owner = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        owner.moveTo(dragon.getX() + 3, dragon.getY(), dragon.getZ());
        var teammate = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        teammate.moveTo(dragon.getX() + 3, dragon.getY(), dragon.getZ() + 3);
        var stranger = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        stranger.moveTo(dragon.getX(), dragon.getY(), dragon.getZ() + 3);

        dragon.tamedFor(owner, true);

        var previousProviders = DragonAllyService.providers;
        boolean previousPassivity = ServerConfig.DRAGON_TEAM_PASSIVITY;
        try {
            DragonAllyService.setProvidersForTest(List.of(stubTeammatesOf(owner.getUUID(), teammate.getUUID())));
            ServerConfig.DRAGON_TEAM_PASSIVITY = true;

            dragon.hurt(helper.getLevel().damageSources().playerAttack(teammate), 1.0F);
            if (dragon.getTarget() == teammate) {
                helper.fail("maybeRetaliate acquired a teammate as its target on an immediate hurt() reaction");
                return;
            }

            // LivingEntity's post-hit invulnerability window (invulnerableDuration = 20
            // ticks, decompiled sources) would otherwise silently swallow a second hurt()
            // call fired with zero ticks elapsed, making super.hurt() return false and
            // DragonAI#wasHurtBy/maybeRetaliate never run for the stranger's hit at all —
            // a test-construction bug, not a defect in the gate under test. Tick past it.
            for (int i = 0; i < 20; i++) {
                dragon.tick();
            }

            dragon.hurt(helper.getLevel().damageSources().playerAttack(stranger), 1.0F);
            if (dragon.getTarget() != stranger) {
                helper.fail("maybeRetaliate did not retaliate against a genuine (non-teamed) attacker — the gate"
                        + " is over-broadly suppressing self-defense, target was: " + dragon.getTarget());
                return;
            }
        } finally {
            DragonAllyService.setProvidersForTest(previousProviders);
            ServerConfig.DRAGON_TEAM_PASSIVITY = previousPassivity;
        }

        helper.succeed();
    }

    /**
     * {@code W8-TEAMS-2}: {@code OwnerHurtByTargetGoal} (dragon defends its owner against
     * whoever last hurt them) must never acquire a teammate as its target, via the
     * extended {@code DragonCombatComponent#wantsToAttack} hook. Seeds the precondition
     * deterministically with {@code owner.setLastHurtByMob(teammate)} (a public vanilla
     * setter that also updates the timestamp — C7-safe, per {@code
     * gate-design-teams.json}'s note on T3-S4).
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void ownerHurtByGoalIgnoresTeammateAttackerOfOwner(ExtendedGameTestHelper helper) {
        var owner = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        owner.moveToCentre();
        var teammate = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        teammate.moveToCorner();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(owner, true);

        var previousProviders = DragonAllyService.providers;
        boolean previousPassivity = ServerConfig.DRAGON_TEAM_PASSIVITY;
        try {
            DragonAllyService.setProvidersForTest(List.of(stubTeammatesOf(owner.getUUID(), teammate.getUUID())));
            ServerConfig.DRAGON_TEAM_PASSIVITY = true;

            // OwnerHurtByTargetGoal.canUse() only re-evaluates when
            // owner.getLastHurtByMobTimestamp() differs from the goal's own last-consumed
            // timestamp (0 until the goal actually starts, decompiled sources). That
            // timestamp is `owner.tickCount` at the moment of the setter call — and
            // ExtendedGameTestHelper's manual `.tick()` calls do NOT advance `tickCount`
            // (only ServerLevel's own entity-tick dispatch does that, per decompiled
            // ServerLevel.java:772 — `p_entity.tickCount++`). Force it nonzero here so the
            // goal is actually exercised instead of trivially never re-checking.
            owner.tickCount = 100;
            owner.setLastHurtByMob(teammate);

            for (int i = 0; i < 60; i++) {
                owner.tick();
                teammate.tick();
                dragon.tick();
                if (dragon.getTarget() == teammate) {
                    helper.fail("OwnerHurtByTargetGoal acquired a teammate as its target while defending the owner"
                            + " (tick " + i + ")");
                    return;
                }
            }
        } finally {
            DragonAllyService.setProvidersForTest(previousProviders);
            ServerConfig.DRAGON_TEAM_PASSIVITY = previousPassivity;
        }

        helper.succeed();
    }

    /**
     * {@code W8-TEAMS-2}: {@code OwnerHurtTargetGoal} (dragon assists the owner's own
     * offense) must refuse to help the owner attack a teammate, and must still assist
     * against a genuine (non-teamed) target — the positive control proving the gate is
     * narrowly scoped. This is the most consequential case per the design's own rationale:
     * an owner who picks a fight with their own teammate must not have the dragon pile on.
     */
    @EmptyTemplate(value = ROOMY_TEMPLATE, floor = true)
    @GameTest
    @TestHolder
    public static void ownerHurtTargetGoalRefusesTeammateButAssistsGenuineTarget(ExtendedGameTestHelper helper) {
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        // See retaliateSynchronousPathIgnoresTeammateHitButAssistsGenuineAttacker for why
        // these use explicit small ALL-POSITIVE offsets and ROOMY_TEMPLATE rather than
        // moveToCentre/moveToCorner in the default 3x3x3 template.
        var owner = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        owner.moveTo(dragon.getX() + 3, dragon.getY(), dragon.getZ());
        var teammate = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        teammate.moveTo(dragon.getX() + 3, dragon.getY(), dragon.getZ() + 3);
        var stranger = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        stranger.moveTo(dragon.getX(), dragon.getY(), dragon.getZ() + 3);

        dragon.tamedFor(owner, true);

        var previousProviders = DragonAllyService.providers;
        boolean previousPassivity = ServerConfig.DRAGON_TEAM_PASSIVITY;
        try {
            DragonAllyService.setProvidersForTest(List.of(stubTeammatesOf(owner.getUUID(), teammate.getUUID())));
            ServerConfig.DRAGON_TEAM_PASSIVITY = true;

            // See ownerHurtByGoalIgnoresTeammateAttackerOfOwner for why tickCount must be
            // forced nonzero: OwnerHurtTargetGoal.canUse() only re-evaluates when
            // owner.getLastHurtMobTimestamp() differs from the goal's own last-consumed
            // timestamp (0 until the goal actually starts), and manual `.tick()` calls
            // never advance `tickCount`.
            owner.tickCount = 100;
            owner.setLastHurtMob(teammate);
            for (int i = 0; i < 60; i++) {
                owner.tick();
                teammate.tick();
                dragon.tick();
                if (dragon.getTarget() == teammate) {
                    helper.fail("OwnerHurtTargetGoal helped the owner attack their own teammate (tick " + i + ")");
                    return;
                }
            }

            owner.setLastHurtMob(stranger);
            boolean assisted = false;
            for (int i = 0; i < 60; i++) {
                owner.tick();
                stranger.tick();
                dragon.tick();
                if (dragon.getTarget() == stranger) {
                    assisted = true;
                    break;
                }
            }
            if (!assisted) {
                helper.fail("OwnerHurtTargetGoal never assisted the owner against a genuine (non-teamed) target —"
                        + " the teammate gate is over-broadly suppressing owner-assist");
                return;
            }
        } finally {
            DragonAllyService.setProvidersForTest(previousProviders);
            ServerConfig.DRAGON_TEAM_PASSIVITY = previousPassivity;
        }

        helper.succeed();
    }

    /**
     * {@code W8-TEAMS-2}/{@code T3-S4}: the brain-tick {@code DragonHurtByTargetGoal}
     * backup path must never latch onto a teammate even across many ticks, AND a refused
     * teammate hit must not leave the goal permanently disarmed — a later hit from a
     * genuine attacker must still arm it normally (required change #5 on T3-S4: {@code
     * canUse()} returning false leaves {@code timestamp} unconsumed by design). Uses
     * {@code setLastHurtByMob} directly (bypassing {@code hurt()}/{@code maybeRetaliate})
     * to isolate this goal's own gate from T3-S3's separate synchronous one.
     */
    @EmptyTemplate(value = ROOMY_TEMPLATE, floor = true)
    @GameTest
    @TestHolder
    public static void hurtByGoalNeverLatchesOntoTeammateButStillArmsForGenuineAttacker(ExtendedGameTestHelper helper) {
        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());

        // See retaliateSynchronousPathIgnoresTeammateHitButAssistsGenuineAttacker for why
        // these use explicit small ALL-POSITIVE offsets and ROOMY_TEMPLATE rather than
        // moveToCentre/moveToCorner in the default 3x3x3 template.
        var owner = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        owner.moveTo(dragon.getX() + 3, dragon.getY(), dragon.getZ());
        var teammate = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        teammate.moveTo(dragon.getX() + 3, dragon.getY(), dragon.getZ() + 3);
        var stranger = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        stranger.moveTo(dragon.getX(), dragon.getY(), dragon.getZ() + 3);

        dragon.tamedFor(owner, true);

        var previousProviders = DragonAllyService.providers;
        boolean previousPassivity = ServerConfig.DRAGON_TEAM_PASSIVITY;
        try {
            DragonAllyService.setProvidersForTest(List.of(stubTeammatesOf(owner.getUUID(), teammate.getUUID())));
            ServerConfig.DRAGON_TEAM_PASSIVITY = true;

            // See ownerHurtByGoalIgnoresTeammateAttackerOfOwner for why tickCount must be
            // forced nonzero: DragonHurtByTargetGoal.canUse() (inherited HurtByTargetGoal
            // logic) only re-evaluates when dragon.getLastHurtByMobTimestamp() differs
            // from the goal's own last-consumed timestamp (0 until the goal actually
            // starts), and manual `.tick()` calls never advance `tickCount`.
            dragon.tickCount = 100;
            dragon.setLastHurtByMob(teammate);
            for (int i = 0; i < 60; i++) {
                owner.tick();
                teammate.tick();
                dragon.tick();
                if (dragon.getTarget() == teammate) {
                    helper.fail("DragonHurtByTargetGoal latched onto a teammate across ticks (tick " + i + ")");
                    return;
                }
            }

            dragon.setLastHurtByMob(stranger);
            boolean armed = false;
            for (int i = 0; i < 60; i++) {
                owner.tick();
                stranger.tick();
                dragon.tick();
                if (dragon.getTarget() == stranger) {
                    armed = true;
                    break;
                }
            }
            if (!armed) {
                helper.fail("DragonHurtByTargetGoal never armed for a genuine attacker after an earlier refused"
                        + " teammate hit — a refused canUse() must not permanently disarm the goal");
                return;
            }
        } finally {
            DragonAllyService.setProvidersForTest(previousProviders);
            ServerConfig.DRAGON_TEAM_PASSIVITY = previousPassivity;
        }

        helper.succeed();
    }

    /**
     * {@code W8-TEAMS-2} release path: a fight already IN PROGRESS against a target that
     * only later becomes a teammate (a team forming mid-fight, or an acquisition gap in
     * some other gate) must actually end — the {@code EraseMemoryIf} entry in {@code
     * DragonAI#initFightActivity}. Without it, {@code canUse()}-only gates never revisit
     * an already-running goal, {@code StopAttackingIfTargetInvalid.create()}'s bare
     * no-args form never drops a target on its own, and {@code TargetGoal#canContinueToUse}
     * only drops a target on a VANILLA scoreboard-team match.
     */
    @EmptyTemplate(floor = true)
    @GameTest
    @TestHolder
    public static void fightReleasesWhenHeldTargetBecomesATeammateMidFight(ExtendedGameTestHelper helper) {
        var owner = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        owner.moveToCentre();
        var target = helper.makeTickingMockServerPlayerInLevel(GameType.DEFAULT_MODE);
        target.moveToCorner();

        var dragon = helper.spawn(ModEntities.DRAGON_ENTITY.get(), DMRTestConstants.TEST_POS);
        dragon.setBreed(DragonBreedsRegistry.getDefault());
        dragon.tamedFor(owner, true);

        var previousProviders = DragonAllyService.providers;
        boolean previousPassivity = ServerConfig.DRAGON_TEAM_PASSIVITY;
        try {
            DragonAllyService.setProvidersForTest(List.of());
            ServerConfig.DRAGON_TEAM_PASSIVITY = true;

            // Fight already in progress, acquired while `target` was a genuine stranger.
            dragon.setTarget(target);
            if (dragon.getTarget() != target) {
                helper.fail("Precondition failed: could not seed an in-progress fight against `target`");
                return;
            }

            // A team forms mid-fight.
            DragonAllyService.setProvidersForTest(List.of(stubTeammatesOf(owner.getUUID(), target.getUUID())));

            boolean released = false;
            for (int i = 0; i < 60; i++) {
                owner.tick();
                target.tick();
                dragon.tick();
                if (dragon.getTarget() == null) {
                    released = true;
                    break;
                }
            }
            if (!released) {
                helper.fail("Dragon kept an in-progress attack target after that target became a teammate"
                        + " mid-fight — the FIGHT activity's release path did not fire");
                return;
            }
        } finally {
            DragonAllyService.setProvidersForTest(previousProviders);
            ServerConfig.DRAGON_TEAM_PASSIVITY = previousPassivity;
        }

        helper.succeed();
    }
}
