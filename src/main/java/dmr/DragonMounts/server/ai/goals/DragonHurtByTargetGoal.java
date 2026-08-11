package dmr.DragonMounts.server.ai.goals;

import dmr.DragonMounts.server.ai.teams.DragonAllyService;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

/**
 * Thin {@code canUse()}-gated subclass of vanilla {@link HurtByTargetGoal} ({@code
 * T3-S4}/{@code W8-TEAMS-2}): refuses to acquire a teammate of the dragon's owner as a
 * retaliation target via the BRAIN-TICK backup path — distinct from {@code
 * DragonAI#maybeRetaliate}'s synchronous {@code hurt()}-time path, this one persists
 * across ticks for as long as {@code getLastHurtByMob()} stays set.
 *
 * <p>
 * Only this one of the three vanilla {@code TargetGoal} classes DMR wires in gets a
 * subclass. {@code OwnerHurtByTargetGoal}/{@code OwnerHurtTargetGoal} both already call
 * {@code TamableAnimal#wantsToAttack(target, owner)} from their own {@code canUse()}
 * (decompiled sources, verified against the actual {@code 21.1.176} jar), which {@link
 * dmr.DragonMounts.server.entity.dragon.DragonCombatComponent} already overrides —
 * extending that ONE hook covers both of those paths without two more classes. {@code
 * HurtByTargetGoal} does NOT consult {@code wantsToAttack} (its {@code canUse()} only
 * calls {@code canAttack(livingentity, HURT_BY_TARGETING)}), so it needs its own gate
 * here.
 *
 * <p>
 * {@code canUse()} returning {@code false} here means {@code GoalWrapper.tryStart} never
 * calls {@code start()}, so none of {@code TargetGoal}'s internal fields ({@code
 * targetMob}, {@code timestamp}) are ever touched for a refused teammate — critically,
 * this goal's own {@code timestamp} field stays UNCONSUMED, so a subsequent hit from a
 * genuine non-teammate attacker still arms the goal normally (see {@code
 * DragonTeamPassivityTests#hurtByGoalStillArmsAfterATeammateHitThenAGenuineHit}). This
 * avoids the goal-starvation hazard a central {@code DragonCombatComponent#setTarget}
 * choke point would have introduced (evaluated and rejected during design — a blocked
 * goal there would still report itself {@code RUNNING} to {@code GoalWrapper}, starving
 * the other two goals sharing the same {@code RunningPolicy.RUN_ONE} slot).
 */
public class DragonHurtByTargetGoal extends HurtByTargetGoal {

    private final TameableDragonEntity dragon;

    public DragonHurtByTargetGoal(TameableDragonEntity dragon) {
        super(dragon);
        this.dragon = dragon;
    }

    @Override
    public boolean canUse() {
        LivingEntity attacker = dragon.getLastHurtByMob();
        if (attacker != null && DragonAllyService.isTeammateOfOwner(dragon, attacker)) {
            return false;
        }
        return super.canUse();
    }
}
