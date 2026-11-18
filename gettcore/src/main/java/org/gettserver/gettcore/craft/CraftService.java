package org.gettserver.gettcore.craft;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.craft.CraftConfig.Recipe;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.EconomyData;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.Ledger;
import org.gettserver.gettcore.network.OpenCraftPayload;
import org.gettserver.gettcore.preset.ItemPresets;

/**
 * Crafting at the craft bench (or from an NPC dialogue): the player picks a recipe in the crafting screen, the server
 * checks the inventory again, takes the ingredients and the gold and gives the result at once. The screen can only be
 * used near where it was opened.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class CraftService {
    private static final double MAX_DISTANCE_SQ = 8 * 8;

    /** Where each player opened the crafting screen. */
    private static final Map<UUID, Vec3> OPENED = new HashMap<>();

    private CraftService() {}

    public static void open(ServerPlayer player, Vec3 at) {
        OPENED.put(player.getUUID(), at);
        PacketDistributor.sendToPlayer(player, new OpenCraftPayload());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OPENED.remove(event.getEntity().getUUID());
    }

    public static void craft(ServerPlayer player, String recipeId) {
        Vec3 at = OPENED.get(player.getUUID());
        if (at == null || player.position().distanceToSqr(at) > MAX_DISTANCE_SQ) {
            fail(player, "gettcore.craft.err.too_far");
            return;
        }
        Optional<Recipe> found = GettData.get().crafting().recipe(recipeId);
        if (found.isEmpty()) {
            fail(player, "gettcore.craft.err.unknown");
            return;
        }
        Recipe recipe = found.get();
        ResourceLocation resultId = ResourceLocation.tryParse(recipe.result());
        ItemStack proto = resultId == null ? ItemStack.EMPTY : ItemPresets.stack(resultId, 1);
        if (proto.isEmpty()) {
            fail(player, "gettcore.craft.err.no_result");
            return;
        }
        CraftInventory.Plan plan = CraftInventory.plan(recipe, player.getInventory().items);
        if (!plan.craftable()) {
            fail(player, "gettcore.craft.err.missing");
            return;
        }
        if (recipe.gold() > 0) {
            EconomyData data = EconomyData.get(player.getServer());
            if (data.ledger().withdraw(player.getUUID(), recipe.gold()) != Ledger.Result.OK) {
                fail(player, "gettcore.msg.insufficient");
                return;
            }
            data.changed(player.getUUID());
        }
        CraftInventory.consume(plan, player.getInventory().items);
        // In stacks of the item's own size; what does not fit is dropped at the player's feet.
        int left = recipe.count();
        while (left > 0) {
            ItemStack give = proto.copyWithCount(Math.min(left, proto.getMaxStackSize()));
            left -= give.getCount();
            if (!player.getInventory().add(give) && !give.isEmpty()) player.drop(give, false);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        player.level().playSound(null, player.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.PLAYERS, 0.8f, 1f);
        Component name = proto.getHoverName();
        player.sendSystemMessage((recipe.gold() > 0
                ? Component.translatable("gettcore.craft.done_gold", name, recipe.count(), Gold.format(recipe.gold()))
                : Component.translatable("gettcore.craft.done", name, recipe.count())).withStyle(ChatFormatting.YELLOW));
    }

    private static void fail(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.RED));
    }
}
