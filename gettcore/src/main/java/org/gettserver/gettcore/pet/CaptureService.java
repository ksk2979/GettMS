package org.gettserver.gettcore.pet;

import com.crispytwig.naturalist.world.entity.variant.DataDrivenVariantAnimal;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.pet.CaptureRules.Verdict;
import org.gettserver.gettcore.pet.boss.GettBoss;
import org.gettserver.gettcore.pet.entity.GettPet;

/** Server side of a capture ball hitting a mob: judge, message the thrower, and turn the mob into an item. */
public final class CaptureService {
    static final String DEFAULT_VARIANT = "default";

    private CaptureService() {}

    public static void onHit(ServerLevel level, ServerPlayer thrower, LivingEntity target, CaptureTier tier) {
        PetConfig config = GettData.get().pets();
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        Optional<PetSpecies> species = config.species(typeId);
        boolean hasOwner = (target instanceof TamableAnimal t && t.getOwnerUUID() != null) || target instanceof GettPet;
        double ratio = target.getMaxHealth() > 0 ? target.getHealth() / target.getMaxHealth() : 0;
        Verdict verdict = CaptureRules.evaluate(species, hasOwner, ratio, tier, config.capture(),
                level.random.nextDouble());

        Component message = switch (verdict) {
            case HEALTH_OUT_OF_RANGE -> Component.translatable(messageKey(verdict), (int) Math.ceil(ratio * 100));
            case CAPTURED -> Component.translatable(messageKey(verdict), target.getName());
            default -> Component.translatable(messageKey(verdict));
        };
        thrower.displayClientMessage(message, true);

        double x = target.getX(), y = target.getY(), z = target.getZ();
        if (verdict == Verdict.CAPTURED) {
            thrower.sendSystemMessage(message);
            String variant = target instanceof DataDrivenVariantAnimal v ? v.getVariantString() : DEFAULT_VARIANT;
            ItemStack stack = capturedStack(new PetKey(typeId.toString(), variant), target instanceof GettBoss);
            target.discard();
            level.addFreshEntity(new ItemEntity(level, x, y, z, stack));
            level.playSound(null, x, y, z, PetRegistry.CAPTURE_SUCCESS.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        } else if (verdict == Verdict.FAILED) {
            level.playSound(null, x, y, z, PetRegistry.CAPTURE_FAIL.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    public static String messageKey(Verdict verdict) {
        return "gettcore.msg.capture." + verdict.name().toLowerCase(Locale.ROOT);
    }

    public static ItemStack capturedStack(PetKey key, boolean boss) {
        ItemStack stack = new ItemStack(PetRegistry.CAPTURED_MOB.get());
        stack.set(PetRegistry.PET_KEY.get(), key);
        stack.set(PetRegistry.PET_BOSS.get(), boss);
        return stack;
    }
}
