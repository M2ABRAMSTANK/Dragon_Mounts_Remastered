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
}
