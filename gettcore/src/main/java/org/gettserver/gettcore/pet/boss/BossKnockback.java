package org.gettserver.gettcore.pet.boss;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.gettserver.gettcore.GettCore;

/**
 * Hits never move a boss. Knockback and explosion knockback resistance (1, set every tick by
 * {@link BossBehavior#applyCombatBases}) stop vanilla hits, arrows, NTGL bullets (they go through
 * {@code LivingEntity.knockback}) and vanilla explosions. What adds velocity directly (NTGL's explosions call
 * {@code setDeltaMovement}, a player walking into the boss) happens between the boss's ticks and is undone here,
 * before the boss's next tick moves it. Pets are not bosses and keep vanilla knockback.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class BossKnockback {
    private BossKnockback() {}

    @SubscribeEvent
    public static void onTickPre(EntityTickEvent.Pre event) {
        if (event.getEntity() instanceof GettBoss boss && !event.getEntity().level().isClientSide()) {
            boss.bossState().undoOutsidePush();
        }
    }
}
