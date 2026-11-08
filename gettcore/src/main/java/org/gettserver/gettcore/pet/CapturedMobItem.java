package org.gettserver.gettcore.pet;

import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.network.OpenObtainPayload;

/**
 * A mob caught by a capture ball; carries {@link PetRegistry#PET_KEY}, {@link PetRegistry#PET_BOSS} and
 * {@link PetRegistry#PET_TIER}.
 */
public class CapturedMobItem extends Item {
    public CapturedMobItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        PetKey key = stack.get(PetRegistry.PET_KEY.get());
        if (key == null) return super.getName(stack);
        return Component.translatable("gettcore.item.captured_mob.named", displayName(key));
    }

    /** Tooltip line with the ball tier the mob was caught with (older items without one show iron). */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        if (!stack.has(PetRegistry.PET_KEY.get())) return;
        tooltip.add(Component.translatable("gettcore.item.captured_mob.tier",
                Component.translatable("gettcore.tier." + CaptureService.tierOf(stack).key()))
                .withStyle(ChatFormatting.GRAY));
    }

    /** Right click opens the obtain screen (the server decides the shown percent); nothing is consumed here. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        PetKey key = stack.get(PetRegistry.PET_KEY.get());
        if (key == null) return InteractionResultHolder.pass(stack);
        if (level.isClientSide()) return InteractionResultHolder.success(stack);
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.pass(stack);

        ResourceLocation wild = ResourceLocation.tryParse(key.speciesId());
        PetConfig config = GettData.get().pets();
        Optional<PetSpecies> species = wild == null ? Optional.empty() : config.species(wild);
        if (species.isEmpty()) {
            sp.sendSystemMessage(Component.translatable("gettcore.msg.obtain.unknown_species")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(stack);
        }
        double percent = CaptureRules.obtainChance(species.get(), CaptureService.tierOf(stack), config.capture());
        boolean boss = Boolean.TRUE.equals(stack.get(PetRegistry.PET_BOSS.get()));
        PacketDistributor.sendToPlayer(sp, new OpenObtainPayload(key, boss, (int) Math.round(percent), wild,
                hand == InteractionHand.MAIN_HAND));
        return InteractionResultHolder.consume(stack);
    }

    /**
     * Species name for items and chat: the entity's name (a boss species' own name already reads "보스 사자"), plus
     * {@code " (variant)"} when {@link PetKey#variantPath} has one ({@code gettcore.variant.<path>}, falling back to
     * the path).
     */
    public static Component displayName(PetKey key) {
        Component entity = EntityType.byString(key.speciesId())
                .<Component>map(EntityType::getDescription)
                .orElseGet(() -> Component.literal(key.speciesId()));
        MutableComponent name = Component.empty().append(entity);
        key.variantPath().ifPresent(path -> name.append(" (")
                .append(Component.translatableWithFallback("gettcore.variant." + path, path))
                .append(")"));
        return name;
    }
}
