package dmr.DragonMounts.server.entity.dragon;

import dmr.DragonMounts.server.entity.DragonConstants;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Base abstract class for dragon entities.
 * This is the foundation of the dragon entity hierarchy.
 */
abstract class CoreDragonComponent extends TamableAnimal
        implements Saddleable, FlyingAnimal, PlayerRideable, GeoEntity, HasCustomInventoryScreen, ContainerListener {

    protected final AnimatableInstanceCache cache;

    protected CoreDragonComponent(EntityType<? extends TamableAnimal> entityType, Level level) {
        super(entityType, level);
        this.cache = GeckoLibUtil.createInstanceCache(this);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        // W8-SYNC-6: getBoundingBox() already scales with the dragon's model size via
        // getDimensions() below (BASE_WIDTH/BASE_HEIGHT * getScale()) — at scale 1 the
        // culling box is already ~12.75 blocks wide against a 2.75-wide hitbox. Only the
        // ADDITIVE culling margin was flat. Scaling that margin too keeps it generous
        // relative to the (already larger) hitbox for big size-modifier breeds, instead of
        // the margin becoming proportionally smaller as the dragon gets bigger. This is a
        // tune of an already-shipped mechanism, not a fix for an undersized box — it must
        // not be described as resolving #43/#111 (see culling-hypothesis.md's own caveat).
        // Math.max(1.0, ...) FLOORS the multiplier at 1.0 so it can only ever grow the box:
        // babies (getScale() < 1) and default-scale (1.0) adults get a result byte-identical
        // to today's inflate(5, 5, 5) — never shrunk.
        var padding = 5.0 * Math.max(1.0, getScale());
        return getBoundingBox().inflate(padding, padding, padding);
    }

    @Override
    protected AABB getAttackBoundingBox() {
        return super.getAttackBoundingBox().inflate(2, 2, 2);
    }

    @Override
    public Vec3 getLightProbePosition(float p_20309_) {
        return new Vec3(getX(), getY() + getBbHeight(), getZ());
    }

    @Override
    public EntityDimensions getDimensions(Pose poseIn) {
        var height = isInSittingPose() ? 2.15f : isShiftKeyDown() ? 2.5f : DragonConstants.BASE_HEIGHT;
        var scale = getScale();
        var dimWidth = DragonConstants.BASE_WIDTH * scale;
        var dimHeight = height * scale;
        return EntityDimensions.scalable(dimWidth, dimHeight)
                .withAttachments(EntityAttachments.builder()
                        .attach(EntityAttachment.PASSENGER, 0.0F, dimHeight - 0.15625F, getScale()));
    }

    public TameableDragonEntity getDragon() {
        return (TameableDragonEntity) this;
    }
}
