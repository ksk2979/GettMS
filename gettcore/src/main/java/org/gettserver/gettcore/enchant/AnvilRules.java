package org.gettserver.gettcore.enchant;

import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.enchant.SpecialEnchants.Rolled;

/**
 * 모루 규칙: 특수 능력은 합치기(레벨업)·다른 도구로 옮기기·덮어쓰기가 불가능하다.
 * 재료 수리와 바닐라 인챈트 책 합성은 그대로 허용. 특수 능력 제거는 숫돌로만.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class AnvilRules {
    private AnvilRules() {}

    /**
     * 결과물까지 보고 판단하는 순수 규칙.
     * 오른쪽이 같은 도구이고 특수 능력이 있으면 금지(옮기기·합치기), 왼쪽에 없는데 오른쪽 도구에 있으면 금지,
     * 왼쪽에 있으면 결과물의 특수 능력이 왼쪽과 같아야(능력·레벨) 한다.
     */
    public static boolean allowed(Optional<Rolled> left, Optional<Rolled> rightSpecial, boolean rightIsSameTool,
                                  Optional<Rolled> output) {
        if (rightSpecial.isPresent() && rightIsSameTool) return false;
        if (left.isEmpty() && rightSpecial.isPresent()) return false;
        if (left.isPresent()) return left.equals(output);
        return true;
    }

    /**
     * {@link AnvilUpdateEvent}는 바닐라가 결과물을 계산하기 전에 발생하므로 결과물 없이 입력만으로 판단한다.
     * 오른쪽이 같은 종류의 도구이면서 특수 능력이 있거나(합치기·옮기기), 오른쪽 책에 특수 능력이 있으면(덮어쓰기·추가) 취소.
     */
    public static boolean shouldCancel(Optional<Rolled> left, Optional<Rolled> right, boolean rightIsSameTool,
                                       boolean rightBookHasSpecial) {
        if (rightBookHasSpecial) return true;
        return right.isPresent() && rightIsSameTool;
    }

    @SubscribeEvent
    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (right.isEmpty()) return;
        var registries = event.getPlayer().level().registryAccess();
        Optional<Rolled> leftSpecial = SpecialEnchants.current(left, registries);
        Optional<Rolled> rightSpecial = SpecialEnchants.current(right, registries);
        var rightTool = SpecialEnchants.toolOf(right);
        boolean sameTool = rightTool.isPresent() && rightTool.equals(SpecialEnchants.toolOf(left));
        boolean bookHasSpecial = right.is(Items.ENCHANTED_BOOK) && rightSpecial.isPresent();
        if (shouldCancel(leftSpecial, rightSpecial, sameTool, bookHasSpecial)) {
            event.setCanceled(true);
        }
    }
}
