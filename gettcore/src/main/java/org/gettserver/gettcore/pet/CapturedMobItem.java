package org.gettserver.gettcore.pet;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** A mob caught by a capture ball; carries {@link PetRegistry#PET_KEY} and {@link PetRegistry#PET_BOSS}. */
public class CapturedMobItem extends Item {
    public CapturedMobItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        PetKey key = stack.get(PetRegistry.PET_KEY.get());
        if (key == null) return super.getName(stack);
        return EntityType.byString(key.speciesId())
                .<Component>map(type -> Component.translatable("gettcore.item.captured_mob.named", type.getDescription()))
                .orElseGet(() -> super.getName(stack));
    }
}
