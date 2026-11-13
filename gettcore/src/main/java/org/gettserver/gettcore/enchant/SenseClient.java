package org.gettserver.gettcore.enchant;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.Tool;

/**
 * 감지(투구): 범위 안의 적대 몹을 쓴 사람의 화면에서만 발광 윤곽선으로 그린다. 서버는 관여하지 않는다 —
 * 클라이언트가 이미 알고 있는 엔티티에 윤곽선만 더한다({@code mixin.MinecraftMixin}).
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class SenseClient {
    private static final double MAX_RANGE = 64;
    /** 감지 범위의 제곱. 능력이 없으면 0. 틱마다 다시 계산한다. */
    private static double rangeSq;

    private SenseClient() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        double range = 0;
        if (player != null) {
            ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
            if (!helmet.isEmpty() && SpecialEnchants.toolOf(helmet).filter(Tool.HELMET::equals).isPresent()) {
                int lvl = SpecialEnchants.level(helmet, Ability.SENSE, player.registryAccess());
                if (lvl > 0) range = Math.min(MAX_RANGE, ClientEnchantConfig.get().armor().sense().get(lvl));
            }
        }
        rangeSq = range > 0 ? range * range : 0;
    }

    public static boolean glows(Entity entity) {
        if (rangeSq <= 0 || !(entity instanceof Enemy) || !entity.isAlive()) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && entity.distanceToSqr(player) <= rangeSq;
    }
}
