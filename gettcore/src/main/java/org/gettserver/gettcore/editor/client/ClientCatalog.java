package org.gettserver.gettcore.editor.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.gettserver.gettcore.equipment.EquipmentEffects;
import org.gettserver.gettcore.equipment.EquipmentRules;
import org.gettserver.gettcore.pet.PetRegistry;

/**
 * Item/entity/tag catalog for the editor, with names in the client's current language. Client only.
 * Built on the client main thread (registries and tags are owned by it) and cached per language until
 * {@link #invalidate()} (logout, tag update).
 */
public final class ClientCatalog {
    private record Cached(String language, String json) {}

    private static volatile Cached cache;

    private ClientCatalog() {}

    /** Catalog JSON; safe to call from any thread (hops to the client thread to build). */
    public static String json() {
        Minecraft mc = Minecraft.getInstance();
        String lang = mc.getLanguageManager().getSelected();
        Cached c = cache;
        if (c != null && c.language().equals(lang)) return c.json();
        String json;
        if (mc.isSameThread()) {
            json = buildAndCache();
        } else {
            try {
                json = mc.submit(ClientCatalog::buildAndCache).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while building catalog", e);
            } catch (Exception e) {
                throw new IllegalStateException("could not build catalog", e);
            }
        }
        return json;
    }

    /** Call on the client thread. */
    public static void invalidate() {
        cache = null;
    }

    /** Client thread only, like {@link #invalidate()}, so a stale build cannot overwrite a fresh invalidation. */
    private static String buildAndCache() {
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        String json = build();
        cache = new Cached(lang, json);
        return json;
    }

    private static String build() {
        List<CatalogJson.Entry> items = new ArrayList<>();
        List<CatalogJson.Gear> gear = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            items.add(new CatalogJson.Entry(id.toString(), item.getDescription().getString(), modName(id)));
            EquipmentRules.Gear g;
            try {
                g = EquipmentEffects.describe(new ItemStack(item));
            } catch (RuntimeException e) {
                continue;   // 다른 모드 아이템이 빈 스택 수정자 계산에서 예외를 던져도 목록은 계속 만든다
            }
            if (g != null) {
                gear.add(new CatalogJson.Gear(id.toString(), item.getDescription().getString(), modName(id), g.kind(),
                        g.slot(), g.values().get(EquipmentRules.Stat.ATTACK_DAMAGE),
                        g.values().get(EquipmentRules.Stat.ATTACK_SPEED), g.values().get(EquipmentRules.Stat.ARMOR),
                        g.values().get(EquipmentRules.Stat.ARMOR_TOUGHNESS),
                        g.values().get(EquipmentRules.Stat.KNOCKBACK_RESISTANCE)));
            }
        }
        List<CatalogJson.Entry> entities = new ArrayList<>();
        List<CatalogJson.MobStat> mobs = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String name = type.getDescription().getString();
            entities.add(new CatalogJson.Entry(id.toString(), name, modName(id)));
            if (type != EntityType.PLAYER && DefaultAttributes.hasSupplier(type)) {
                @SuppressWarnings("unchecked")
                AttributeSupplier attrs = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
                mobs.add(new CatalogJson.MobStat(id.toString(), name, modName(id),
                        type.getCategory().getName(), base(attrs, Attributes.MAX_HEALTH),
                        base(attrs, Attributes.ATTACK_DAMAGE), base(attrs, Attributes.MOVEMENT_SPEED),
                        base(attrs, Attributes.ARMOR)));
            }
        }
        List<String> bossTypes = PetRegistry.BOSS_TYPES.stream().map(h -> h.getId().toString()).toList();
        List<CatalogJson.Entry> effects = new ArrayList<>();
        for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
            if (effect.isInstantenous()) continue;   // 단계 능력에 쓸 수 없음(매 갱신마다 발동)
            ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
            effects.add(new CatalogJson.Entry(id.toString(), effect.getDisplayName().getString(), modName(id)));
        }
        return CatalogJson.build(items, entities, tags(BuiltInRegistries.ITEM), tags(BuiltInRegistries.ENTITY_TYPE),
                bossTypes, effects, mobs, gear);
    }

    private static Double base(AttributeSupplier attrs, Holder<Attribute> attribute) {
        return attrs.hasAttribute(attribute) ? attrs.getBaseValue(attribute) : null;
    }

    private static List<String> tags(Registry<?> registry) {
        return registry.getTagNames().map(t -> t.location().toString()).toList();
    }

    private static String modName(ResourceLocation id) {
        return ModList.get().getModContainerById(id.getNamespace())
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(id.getNamespace());
    }
}
