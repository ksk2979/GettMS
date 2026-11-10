package org.gettserver.gettcore.quest;

import java.util.Optional;
import java.util.function.Predicate;
import org.gettserver.gettcore.pet.entity.GettPet;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** {@link PlayerFacts} read live from a server player (whole inventory incl. armor/offhand, advancements). */
public final class ServerPlayerFacts implements PlayerFacts {
    private final ServerPlayer player;

    public ServerPlayerFacts(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public int countItem(String itemOrTag) {
        Optional<Predicate<ItemStack>> m = itemMatcher(itemOrTag);
        if (m.isEmpty()) return 0;
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && m.get().test(s)) n += s.getCount();
        }
        return n;
    }

    @Override
    public boolean hasAdvancement(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return false;
        AdvancementHolder holder = player.server.getAdvancements().get(rl);
        if (holder == null) return false;
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    /** Removes exactly {@code count} matching items if the player has that many; returns false (nothing removed) if not. */
    public boolean removeItems(String itemOrTag, int count) {
        Optional<Predicate<ItemStack>> m = itemMatcher(itemOrTag);
        if (m.isEmpty()) return false;
        Inventory inv = player.getInventory();
        int[] available = new int[inv.getContainerSize()];
        int total = 0;
        for (int i = 0; i < available.length; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && m.get().test(s)) {
                available[i] = s.getCount();
                total += s.getCount();
            }
        }
        if (total < count) return false;
        int[] take = ItemTargets.removalPlan(available, count);
        for (int i = 0; i < take.length; i++) {
            if (take[i] > 0) inv.removeItem(i, take[i]);
        }
        inv.setChanged();
        player.containerMenu.broadcastChanges();
        return true;
    }

    /** Matcher for an item id or #item tag; empty when the target cannot be parsed. */
    public static Optional<Predicate<ItemStack>> itemMatcher(String itemOrTag) {
        Optional<ItemTargets.Target> t = ItemTargets.parse(itemOrTag);
        if (t.isEmpty()) return Optional.empty();
        ResourceLocation rl = ResourceLocation.tryParse(t.get().id());
        if (rl == null) return Optional.empty();
        if (t.get().tag()) {
            TagKey<net.minecraft.world.item.Item> tag = TagKey.create(Registries.ITEM, rl);
            return Optional.of(s -> s.is(tag));
        }
        return Optional.of(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).equals(rl));
    }

    /** Whether a kill objective target (entity id or #entity_type tag) matches this victim. */
    public static Predicate<String> killMatcher(Entity victim) {
        EntityType<?> type = victim.getType();
        ResourceLocation key = EntityType.getKey(type);
        return target -> "@monster".equals(target) ? isHostileMonster(victim) : ItemTargets.parse(target).map(t -> {
            ResourceLocation rl = ResourceLocation.tryParse(t.id());
            if (rl == null) return false;
            return t.tag() ? type.is(TagKey.create(Registries.ENTITY_TYPE, rl)) : rl.equals(key);
        }).orElse(false);
    }

    private static boolean isHostileMonster(Entity v) {
        return v instanceof Enemy && !(v instanceof GettPet) && !(v instanceof Player);
    }
}
