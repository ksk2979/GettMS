package org.gettserver.gettcore.fieldgold;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.EconomyData;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.Ledger;
import org.gettserver.gettcore.hunting.VariantTag;
import org.gettserver.gettcore.pet.boss.GettBoss;
import org.gettserver.gettcore.pet.entity.GettPet;

/**
 * Field monster kill gold: the player who lands the kill (a pet's kill pays its owner) gets the price of
 * {@link FieldGoldConfig}, shown on the action bar. Hunting zone mobs, bosses and farm spawns (see
 * {@link FieldGoldRules}) pay nothing here.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class FieldGoldEvents {
    private FieldGoldEvents() {}

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!FieldGoldRules.paysFor(event.getSpawnType())) {
            event.getEntity().getPersistentData().putBoolean(FieldGoldRules.NO_GOLD_TAG, true);
        }
    }

    /** LOWEST so other mods' cancellations are already applied; canceled deaths are not received. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        MinecraftServer server = dead.level().getServer();
        if (dead.level().isClientSide() || server == null) return;
        if (!(dead instanceof Enemy) || dead instanceof Player || dead instanceof GettBoss) return;
        if (dead.getPersistentData().getBoolean(FieldGoldRules.NO_GOLD_TAG)) return;
        if (VariantTag.read(dead.getPersistentData()).isPresent()) return;
        ServerPlayer payee = payee(server, event.getSource().getEntity());
        if (payee == null) return;

        AttributeInstance atk = dead.getAttribute(Attributes.ATTACK_DAMAGE);
        long gold = GettData.get().fieldGold().price(BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType()),
                dead.getMaxHealth(), atk != null ? atk.getValue() : 0, dead.getArmorValue());
        if (gold <= 0) return;
        EconomyData econ = EconomyData.get(server);
        if (econ.ledger().deposit(payee.getUUID(), gold) != Ledger.Result.OK) {
            GettCore.LOGGER.warn("gettcore: field kill gold {} for {} could not be paid to {}", Gold.format(gold),
                    dead.getType(), payee.getGameProfile().getName());
            return;
        }
        econ.changed(payee.getUUID());
        payee.displayClientMessage(Component.translatable("gettcore.msg.field.kill_gold", dead.getName(),
                Gold.format(gold)).withStyle(ChatFormatting.GOLD), true);
    }

    private static ServerPlayer payee(MinecraftServer server, Entity killer) {
        if (killer instanceof ServerPlayer p) return p;
        if (killer instanceof GettPet pet && pet.ownerId() != null) {
            return server.getPlayerList().getPlayer(pet.ownerId());
        }
        return null;
    }
}
