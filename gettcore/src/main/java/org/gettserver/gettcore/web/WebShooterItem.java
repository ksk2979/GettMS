package org.gettserver.gettcore.web;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 거미줄 발사기. 우클릭 처리는 클라이언트의 {@code web.client.WebClient} 가 키 입력에서 직접 하고(눌렀을 때 한 번),
 * 서버는 {@link WebService} 가 판정한다. 아이템 자체는 설명만 가진다.
 */
public class WebShooterItem extends Item {
    public WebShooterItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("gettcore.web.use").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("gettcore.web.reel").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("gettcore.web.dual").withStyle(ChatFormatting.DARK_AQUA));
    }
}
