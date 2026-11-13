package org.gettserver.gettcore.detector;

import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;

/** 탐지기: 대상 칸(아이템 1개, 소모 없음)을 번들처럼 커서 우클릭으로 채우고 뺀다. 우클릭하면 주변을 탐지한다. */
public final class DetectorItem extends Item {
    public DetectorItem(Item.Properties properties) {
        super(properties);
    }

    /** 대상 칸의 아이템 사본. 없으면 {@link ItemStack#EMPTY}. */
    public static ItemStack target(ItemStack detector) {
        ItemContainerContents contents = detector.get(GettCore.DETECTOR_TARGET.get());
        if (contents == null) return ItemStack.EMPTY;
        for (ItemStack s : contents.nonEmptyItems()) return s.copy();
        return ItemStack.EMPTY;
    }

    /** EMPTY 면 컴포넌트를 제거한다. 1개만 저장한다. */
    public static void setTarget(ItemStack detector, ItemStack one) {
        if (one.isEmpty()) {
            detector.remove(GettCore.DETECTOR_TARGET.get());
        } else {
            detector.set(GettCore.DETECTOR_TARGET.get(), ItemContainerContents.fromItems(List.of(one.copyWithCount(1))));
        }
    }

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action,
                                            Player player, SlotAccess access) {
        if (action != ClickAction.SECONDARY || !slot.allowModification(player)) return false;
        ItemStack stored = target(stack);
        DetectorConfig config = player.level().isClientSide
                ? ClientDetectorConfig.get() : GettData.get().detector();
        boolean cursorIsTarget = !other.isEmpty() && DetectorTargets.forStack(config, other).isPresent();

        DetectorSlot.Result r = DetectorSlot.click(toSlot(stored), toSlot(other), cursorIsTarget);
        if (!r.accepted()) return false;

        if (other.isEmpty()) {
            // 꺼내기: 저장된 스택 그대로 커서로.
            setTarget(stack, ItemStack.EMPTY);
            access.set(stored);
            playSound(player, false);
        } else if (cursorIsTarget && !sameItem(stored, other)) {
            // 넣기: 새 칸 = 커서 1개(구성요소 유지). 기존 것은 커서(1개였으면) 또는 인벤토리로.
            ItemStack remaining = other.copyWithCount(other.getCount() - 1);
            setTarget(stack, other);
            if (remaining.isEmpty()) {
                access.set(stored);
            } else {
                access.set(remaining);
                if (!stored.isEmpty()) player.getInventory().placeItemBackInInventory(stored);
            }
            playSound(player, true);
        }
        return true;
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && a.getItem() == b.getItem();
    }

    private static DetectorSlot.Stack toSlot(ItemStack s) {
        return s.isEmpty() ? null : new DetectorSlot.Stack(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount());
    }

    private static void playSound(Player player, boolean insert) {
        player.playSound(insert ? SoundEvents.BUNDLE_INSERT : SoundEvents.BUNDLE_REMOVE_ONE, 0.8F,
                0.8F + player.level().getRandom().nextFloat() * 0.4F);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) DetectorService.use(sp, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        return Optional.of(new DetectorTooltip(target(stack)));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("gettcore.detector.slot").withStyle(ChatFormatting.GRAY));
        ItemStack target = target(stack);
        if (target.isEmpty()) {
            lines.add(Component.translatable("gettcore.detector.hint").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.translatable("gettcore.detector.target", target.getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("gettcore.detector.use").withStyle(ChatFormatting.GRAY));
    }
}
