package org.gettserver.gettcore.reinforce;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.gettserver.gettcore.GettCore;

/**
 * 클라이언트 전용: 강화된 겉날개(흉갑 칸 또는 Curios 등 칸)로 활강 중인 로컬 플레이어의 비행 속도·폭죽 부스트. 플레이어 이동은
 * 클라이언트 권한이라 여기서 속도를 고친다(서버는 내구도 무한만 맡음). 수치는 동기화된 {@link ClientReinforceConfig}.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class ElytraClientEffects {
    private static double beforeX;
    private static double beforeZ;
    private static boolean wasGliding;

    private ElytraClientEffects() {}

    @SubscribeEvent
    public static void onTickStart(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        Vec3 v = player.getDeltaMovement();
        beforeX = v.x;
        beforeZ = v.z;
        wasGliding = player.isFallFlying();
    }

    @SubscribeEvent
    public static void onTickEnd(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer player) || !player.isFallFlying()) return;
        ItemStack elytra = ReinforceTargets.wornElytra(player);
        int level = ReinforceRules.level(elytra);
        if (level <= 0) return;
        ReinforceConfig cfg = ClientReinforceConfig.get();
        Vec3 v = player.getDeltaMovement();
        double speed = ReinforceRules.elytraSpeedPercent(level, cfg);
        // 벽에 부딪힌 틱은 속도가 0 으로 꺾여 변화량이 역방향이 되므로 건너뛴다.
        if (wasGliding && speed > 0 && !player.horizontalCollision) {
            double[] h = ElytraBoost.glide(beforeX, beforeZ, v.x, v.z, speed);
            v = new Vec3(h[0], v.y, h[1]);
        }
        double firework = ReinforceRules.elytraFireworkPercent(level, cfg);
        if (firework > 0 && hasOwnRocket(player)) {
            Vec3 look = player.getLookAngle();
            double[] extra = ElytraBoost.fireworkExtra(new double[]{look.x, look.y, look.z},
                    new double[]{v.x, v.y, v.z}, firework);
            v = v.add(extra[0], extra[1], extra[2]);
        }
        player.setDeltaMovement(v);
    }

    /** 이 플레이어에게 붙어 미는 중인 폭죽 로켓이 있는지. */
    private static boolean hasOwnRocket(LocalPlayer player) {
        return !player.level().getEntitiesOfClass(FireworkRocketEntity.class, player.getBoundingBox().inflate(4),
                r -> r.attachedToEntity == player && r.isAlive()).isEmpty();
    }
}
