package org.gettserver.gettcore.pet;

import com.crispytwig.naturalist.world.entity.variant.DataDrivenVariantAnimal;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.RateLimiter;
import org.gettserver.gettcore.pet.CaptureRules.Verdict;
import org.gettserver.gettcore.pet.boss.BossPointData;
import org.gettserver.gettcore.pet.boss.GettBoss;
import org.gettserver.gettcore.pet.entity.GettPet;

/** Server side of a capture ball hitting a mob: judge, message the thrower, and turn the mob into an item. */
public final class CaptureService {
    static final String DEFAULT_VARIANT = "default";
    /** One obtain confirm per server tick (50 ms) per player. */
    private static final RateLimiter OBTAIN_LIMITER = new RateLimiter(50);

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
            if (target instanceof GettBoss boss) {
                BossPointData.get(level.getServer()).onBossGone(boss.pointId(), target.getUUID(),
                        level.getServer().overworld().getGameTime());
            }
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

    /** True if the stack is a captured mob carrying a pet key — the only thing [확정] may act on. */
    public static boolean canConfirm(ItemStack stack) {
        return stack.is(PetRegistry.CAPTURED_MOB.get()) && stack.has(PetRegistry.PET_KEY.get());
    }

    public static String obtainMessageKey(boolean success, boolean duplicate) {
        if (!success) return "gettcore.msg.obtain.escaped";
        return duplicate ? "gettcore.msg.obtain.duplicate" : "gettcore.msg.obtain.success";
    }

    /** C2S [확정]: second-stage roll on the captured mob in {@code hand}; one item is consumed whatever the outcome. */
    public static void confirmObtain(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!canConfirm(stack)) return;
        if (!OBTAIN_LIMITER.tryAcquire(player.getUUID(), System.currentTimeMillis())) return;
        PetKey key = stack.get(PetRegistry.PET_KEY.get());
        boolean boss = Boolean.TRUE.equals(stack.get(PetRegistry.PET_BOSS.get()));
        PetConfig config = GettData.get().pets();
        ResourceLocation wild = ResourceLocation.tryParse(key.speciesId());
        Optional<PetSpecies> species = wild == null ? Optional.empty() : config.species(wild);
        if (species.isEmpty()) {
            player.sendSystemMessage(Component.translatable("gettcore.msg.obtain.unknown_species")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        boolean success = CaptureRules.obtain(species.get(), config.capture(), player.getRandom().nextDouble());
        boolean duplicate = success && !PetData.get(player.server).add(player.getUUID(), key);
        stack.shrink(1);

        String msgKey = obtainMessageKey(success, duplicate);
        Component message = duplicate
                ? Component.translatable(msgKey).withStyle(ChatFormatting.YELLOW)
                : Component.translatable(msgKey, CapturedMobItem.displayName(key, boss))
                        .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED);
        player.sendSystemMessage(message);
        if (success && !duplicate) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
        } else if (!success) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        String outcome = !success ? "escaped" : duplicate ? "duplicate" : "obtained";
        GettCore.LOGGER.info("gettcore: {} obtain {} [{}]{} -> {}", player.getGameProfile().getName(),
                key.speciesId(), key.variant(), boss ? " boss" : "", outcome);
    }
}
