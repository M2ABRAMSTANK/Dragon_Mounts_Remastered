package dmr.DragonMounts.server.ai.sensors;

import dmr.DragonMounts.data.EntityTagProvider;
import dmr.DragonMounts.server.ai.teams.DragonAllyService;
import dmr.DragonMounts.server.entity.DragonAgroState;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.NearestVisibleLivingEntitySensor;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;

public class DragonAttackablesSensor extends NearestVisibleLivingEntitySensor {

    @Override
    protected boolean isMatchingEntity(LivingEntity attacker, LivingEntity target) {
        var dragon = (TameableDragonEntity) attacker;

        // UNCHANGED — same tokens, same order, single evaluation — this is what keeps
        // behavior for non-teamed AND vanilla-teamed pairs provably byte-identical (C4).
        var isNotAllied = TargetingConditions.forCombat()
                .selector(s -> !s.isAlliedTo(dragon)
                        && (!dragon.isTame() || (dragon.getOwner() != null && !s.isAlliedTo(dragon.getOwner()))))
                .test(attacker, target);

        // W8-TEAMS-3/T3-S2 (re-specified per gate-design-teams.json, rejecting the
        // original two-TargetingConditions-evaluation design): `!target.isAlliedTo(dragon)`
        // guarantees modOnlyAlly is false for every pair vanilla already recognizes as
        // allied, so this is additive-only against the legacy behavior above.
        // DragonAllyService.isTeammateOfOwner is used (not a raw isAllied(target, owner)
        // call) so a teammate's tamed pet is covered too via its own owner delegation, and
        // so the check still holds when the owner is offline/cross-dimension (getOwner()
        // returning null here just makes isTeammateOfOwner fall through to its own
        // UUID-keyed path rather than silently reading as "not a teammate").
        var modOnlyAlly = dragon.isTame() && !target.isAlliedTo(dragon) && DragonAllyService.isTeammateOfOwner(dragon, target);

        if (dragon.getOwner() instanceof Player player) {
            // `&& !modOnlyAlly` is the actual fix: the legacy override's own trigger
            // (`!isNotAllied`) fires on ANY TargetingConditions failure — not only
            // allegiance — and DragonCombatComponent#canAttack returns false WHENEVER THE
            // OWNER IS RIDING (a controlling passenger), i.e. exactly the operator's
            // primary scenario ("owner accidentally clips their teammate while flying").
            // Without this term the override would still fire on an FTB/OPAC teammate in
            // that case, leaving the mod's headline use case unfixed.
            if (player.getLastHurtMob() == target && !isNotAllied && !modOnlyAlly) return true;
        }

        // Only hunt non-tamed dragons from other spawn groups
        if (target instanceof TameableDragonEntity otherDragon) {
            if (dragon.getSpawnGroupId() == otherDragon.getSpawnGroupId()) {
                return false;
            } else if (!otherDragon.isTame() && !dragon.isTame()) {
                return true;
            }
        }

        var predicate = EntityPredicate.Builder.entity()
                .of(
                        dragon.isTame()
                                ? EntityTagProvider.DRAGON_HUNTING_TARGET
                                : EntityTagProvider.WILD_DRAGON_HUNTING_TARGET)
                .build();
        var predicateMatches = predicate.matches((ServerLevel) dragon.level, target.position(), target);
        var canHunt = !dragon.isTame() || dragon.getAgroState() == DragonAgroState.AGGRESSIVE;

        return ((this.isClose(attacker, target) && Sensor.isEntityAttackable(attacker, target) && isNotAllied)
                && predicateMatches
                && canHunt
                && !modOnlyAlly);
    }

    private boolean isClose(LivingEntity attacker, LivingEntity target) {
        return target.distanceToSqr(attacker) <= (((TameableDragonEntity) attacker).isTame() ? 16.0D : 64.0D);
    }

    @Override
    protected MemoryModuleType<LivingEntity> getMemory() {
        return MemoryModuleType.NEAREST_ATTACKABLE;
    }
}
