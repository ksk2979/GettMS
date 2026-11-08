package org.gettserver.gettcore.pet;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * Capture ball; the tier picks the capture chance column in pets.json. Holding right click aims (zoom + chance HUD,
 * client side); left click throws ({@link CaptureService#throwBall}).
 */
public class CaptureBallItem extends Item {
    private final CaptureTier tier;

    public CaptureBallItem(CaptureTier tier, Item.Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public CaptureTier tier() {
        return tier;
    }

    /** Starts aiming; nothing is thrown or consumed. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    /** No bow pose: the ball stays visible in hand while aiming. */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        return stack;
    }
}
