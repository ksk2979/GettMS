package org.gettserver.gettcore.pet;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Flying capture ball. The thrown ball stack lives in the projectile's synched item slot, so the client
 * renders the right tier and the server reads the tier from it on hit.
 */
public class ThrownCaptureBall extends ThrowableItemProjectile {
    public ThrownCaptureBall(EntityType<? extends ThrownCaptureBall> type, Level level) {
        super(type, level);
    }

    public ThrownCaptureBall(Level level, LivingEntity shooter) {
        super(PetRegistry.THROWN_CAPTURE_BALL.get(), shooter, level);
    }

    @Override
    protected Item getDefaultItem() {
        return PetRegistry.CAPTURE_BALL_IRON.get();
    }

    public CaptureTier tier() {
        return getItem().getItem() instanceof CaptureBallItem ball ? ball.tier() : CaptureTier.IRON;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (level() instanceof ServerLevel level && getOwner() instanceof ServerPlayer thrower
                && result.getEntity() instanceof LivingEntity target) {
            CaptureService.onHit(level, thrower, target, tier());
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!level().isClientSide()) {
            discard();
        }
    }
}
