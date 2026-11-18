package org.gettserver.gettcore.nick;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 닉네임 변경권. Using it opens the nickname box on the client; the ticket is taken by the server only when the typed
 * nickname was accepted ({@link NicknameService#onSet}).
 */
public class NicknameTicketItem extends Item {
    /** Opens the nickname box; set by the client at start-up so the server never loads the screen class. */
    public static Runnable OPEN_SCREEN = () -> {};

    public NicknameTicketItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) OPEN_SCREEN.run();
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.gettcore.nickname_ticket.desc").withStyle(ChatFormatting.GRAY));
    }
}
