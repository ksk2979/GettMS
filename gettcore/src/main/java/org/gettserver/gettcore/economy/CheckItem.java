package org.gettserver.gettcore.economy;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.gettserver.gettcore.GettCore;

public class CheckItem extends Item {
    public CheckItem(Item.Properties properties) {
        super(properties);
    }

    public static ItemStack create(long amount, String issuer) {
        ItemStack stack = new ItemStack(GettCore.CHECK.get());
        stack.set(GettCore.CHECK_AMOUNT.get(), amount);
        stack.set(GettCore.CHECK_ISSUER.get(), issuer);
        return stack;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            long amount = stack.getOrDefault(GettCore.CHECK_AMOUNT.get(), 0L);
            EconomyData data = EconomyData.get(sp.server);
            UUID id = sp.getUUID();
            if (data.ledger().deposit(id, amount) == Ledger.Result.OK) {
                stack.shrink(1);
                data.changed(id);
                sp.sendSystemMessage(Component.translatable("gettcore.msg.check_redeemed", Gold.format(amount)));
            } else {
                sp.sendSystemMessage(Component.translatable("gettcore.msg.invalid_amount"));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        long amount = stack.getOrDefault(GettCore.CHECK_AMOUNT.get(), 0L);
        tooltip.add(Component.translatable("gettcore.tooltip.check_amount", Gold.format(amount)));
    }
}
