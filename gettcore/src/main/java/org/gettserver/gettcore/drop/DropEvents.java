package org.gettserver.gettcore.drop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.hunting.HuntingConfig;
import org.gettserver.gettcore.hunting.VariantTag;
import org.gettserver.gettcore.preset.ItemPresets;

/**
 * Extra kill drops, added to what the mob drops anyway: the entity type's list from mobs.json (bosses are entity
 * types too) plus, for a hunting variant, that variant's own list. Only when a player made the kill; never for
 * players or owned animals. Items are plain ids or item presets ({@code preset:<name>}).
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class DropEvents {
    private DropEvents() {}

    /** The lists that apply to a dead mob: its type's, then its hunting variant's (looked up live by zone and name). */
    static List<Drop> dropsFor(LivingEntity dead) {
        List<Drop> out = new ArrayList<>(GettData.get().mobs()
                .dropsOf(BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType())));
        Optional<VariantTag> tag = VariantTag.read(dead.getPersistentData());
        if (tag.isPresent() && !tag.get().test()) {
            for (HuntingConfig.Zone z : GettData.get().hunting().zones()) {
                if (!z.id().equals(tag.get().zone())) continue;
                z.entry(tag.get().entry()).ifPresent(e -> out.addAll(e.drops()));
                break;
            }
        }
        return out;
    }

    /** The stacks one kill gives (each split by its max stack size); unknown items are skipped. */
    static List<ItemStack> roll(List<Drop> drops, RandomSource random) {
        List<ItemStack> out = new ArrayList<>();
        for (Drop d : drops) {
            int n = d.roll(random);
            if (n <= 0) continue;
            ItemStack proto = ItemPresets.stack(d.item(), 1);
            if (proto.isEmpty()) continue;
            int max = proto.getMaxStackSize();
            while (n > 0) {
                int take = Math.min(n, max);
                out.add(proto.copyWithCount(take));
                n -= take;
            }
        }
        return out;
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide() || dead instanceof Player) return;
        if (dead instanceof OwnableEntity owned && owned.getOwnerUUID() != null) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer)) return;
        List<Drop> drops = dropsFor(dead);
        if (drops.isEmpty()) return;
        for (ItemStack stack : roll(drops, dead.getRandom())) {
            ItemEntity item = new ItemEntity(dead.level(), dead.getX(), dead.getY(), dead.getZ(), stack);
            item.setDefaultPickUpDelay();
            event.getDrops().add(item);
        }
    }
}
