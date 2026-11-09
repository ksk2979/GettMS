package org.gettserver.gettcore.reinforce;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.GettCore;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

/**
 * Curios(선택 의존성) 연동: Curios 칸(Elytra Slot 모드가 겉날개를 넣는 등 칸)에 낀 강화 대상 겉날개를 찾는다. GettCore 생성자의
 * {@code ModList.get().isLoaded("curios")} 분기 안에서만 참조해야 Curios 없는 환경에서 이 클래스가 로드되지 않는다.
 */
public final class CuriosBridge {
    private CuriosBridge() {}

    public static void install() {
        ReinforceTargets.curiosElytra = CuriosBridge::findElytra;
        GettCore.LOGGER.info("gettcore: Curios found — elytra in curios slots counts for reinforce");
    }

    private static ItemStack findElytra(LivingEntity entity) {
        return CuriosApi.getCuriosInventory(entity)
                .flatMap(inv -> inv.findFirstCurio(ReinforceTargets::isElytra))
                .map(SlotResult::stack)
                .orElse(ItemStack.EMPTY);
    }
}
