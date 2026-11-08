package org.gettserver.gettcore.pet;

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
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.network.OpenObtainPayload;

/** A mob caught by a capture ball; carries {@link PetRegistry#PET_KEY} and {@link PetRegistry#PET_BOSS}. */
public class CapturedMobItem extends Item {
    public CapturedMobItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        PetKey key = stack.get(PetRegistry.PET_KEY.get());
        if (key == null) return super.getName(stack);
        return Component.translatable("gettcore.item.captured_mob.named",
                displayName(key, Boolean.TRUE.equals(stack.get(PetRegistry.PET_BOSS.get()))));
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
        double percent = species.get().obtainPercent() > 0
                ? species.get().obtainPercent() : config.capture().obtainPercent();
        boolean boss = Boolean.TRUE.equals(stack.get(PetRegistry.PET_BOSS.get()));
        PacketDistributor.sendToPlayer(sp, new OpenObtainPayload(key, boss, (int) Math.round(percent), wild,
                hand == InteractionHand.MAIN_HAND));
        return InteractionResultHolder.consume(stack);
    }

    /**
     * Species name for items and chat: the wild entity's name, plus {@code " (variant)"} unless the variant is
     * default ({@code gettcore.variant.<variant>}, falling back to the raw id), prefixed by
     * {@code gettcore.boss_prefix} for bosses.
     */
    public static Component displayName(PetKey key, boolean boss) {
        Component entity = EntityType.byString(key.speciesId())
                .<Component>map(EntityType::getDescription)
                .orElseGet(() -> Component.literal(key.speciesId()));
        MutableComponent name = Component.empty();
        if (boss) name.append(Component.translatable("gettcore.boss_prefix"));
        name.append(entity);
        String variant = key.variant();
        if (!variant.isEmpty() && !CaptureService.DEFAULT_VARIANT.equals(variant)) {
            name.append(" (")
                    .append(Component.translatableWithFallback("gettcore.variant." + variant, variant))
                    .append(")");
        }
        return name;
    }
}
