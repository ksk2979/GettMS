package org.gettserver.gettcore.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.gettserver.gettcore.data.GettData;

public class ReturnStoneItem extends Item {
    public ReturnStoneItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            if (ReturnStoneTicker.isCasting(sp.getUUID())) {
                return InteractionResultHolder.fail(stack);
            }
            int seconds = Math.max(1, GettData.get().economy().returnCastSeconds());
            ReturnStoneTicker.start(sp, hand, seconds * 20);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
