package org.gettserver.gettcore.item;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.gettserver.gettcore.data.GettData;

public class XpOrbItem extends Item {
    public XpOrbItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            int amount = GettData.get().economy().xpOrbAmount();
            sp.giveExperiencePoints(amount);
            stack.shrink(1);
            level.playSound(null, sp.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1f);
            sp.sendSystemMessage(Component.translatable("gettcore.msg.xp_gained", amount));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
