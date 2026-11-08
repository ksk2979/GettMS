package org.gettserver.gettcore.pet;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvent;
import com.crispytwig.naturalist.world.entity.animal.bear.Bear;
import com.crispytwig.naturalist.world.entity.animal.bear.BlackBear;
import com.crispytwig.naturalist.world.entity.animal.elephant.Elephant;
import com.crispytwig.naturalist.world.entity.animal.elephant.Mammoth;
import com.crispytwig.naturalist.world.entity.animal.giraffe.Giraffe;
import com.crispytwig.naturalist.world.entity.animal.hippo.Hippo;
import com.crispytwig.naturalist.world.entity.animal.lion.Lion;
import com.crispytwig.naturalist.world.entity.animal.ostrich.Ostrich;
import com.crispytwig.naturalist.world.entity.animal.tiger.Tiger;
import com.crispytwig.naturalist.world.entity.animal.vulture.Vulture;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.pet.boss.BossBear;
import org.gettserver.gettcore.pet.boss.BossElephant;
import org.gettserver.gettcore.pet.boss.BossLion;
import org.gettserver.gettcore.pet.boss.BossMammoth;
import org.gettserver.gettcore.pet.boss.BossTiger;
import org.gettserver.gettcore.pet.boss.BossVulture;
import org.gettserver.gettcore.pet.entity.PetBear;
import org.gettserver.gettcore.pet.entity.PetBlackBear;
import org.gettserver.gettcore.pet.entity.PetElephant;
import org.gettserver.gettcore.pet.entity.PetGiraffe;
import org.gettserver.gettcore.pet.entity.PetHippo;
import org.gettserver.gettcore.pet.entity.PetLion;
import org.gettserver.gettcore.pet.entity.PetMammoth;
import org.gettserver.gettcore.pet.entity.PetOstrich;
import org.gettserver.gettcore.pet.entity.PetTiger;
import org.gettserver.gettcore.pet.entity.PetVulture;

/**
 * Registers for the pet system: capture balls, the captured-mob item and its components, the projectile, the pet
 * entities, sounds.
 */
public final class PetRegistry {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GettCore.MOD_ID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, GettCore.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, GettCore.MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, GettCore.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PetKey>> PET_KEY =
            DATA_COMPONENTS.registerComponentType("pet_key",
                    b -> b.persistent(PetKey.CODEC).networkSynchronized(PetKey.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> PET_BOSS =
            DATA_COMPONENTS.registerComponentType("pet_boss",
                    b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));

    public static final DeferredItem<CaptureBallItem> CAPTURE_BALL_IRON = ball("iron", CaptureTier.IRON);
    public static final DeferredItem<CaptureBallItem> CAPTURE_BALL_GOLD = ball("gold", CaptureTier.GOLD);
    public static final DeferredItem<CaptureBallItem> CAPTURE_BALL_DIAMOND = ball("diamond", CaptureTier.DIAMOND);
    public static final DeferredItem<CaptureBallItem> CAPTURE_BALL_NETHERITE =
            ball("netherite", CaptureTier.NETHERITE);
    public static final DeferredItem<CapturedMobItem> CAPTURED_MOB =
            ITEMS.registerItem("captured_mob", CapturedMobItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredHolder<EntityType<?>, EntityType<ThrownCaptureBall>> THROWN_CAPTURE_BALL =
            ENTITIES.register("thrown_capture_ball", () -> EntityType.Builder
                    .<ThrownCaptureBall>of(ThrownCaptureBall::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F).clientTrackingRange(4).updateInterval(10)
                    .build("thrown_capture_ball"));

    // Pet entities: hitboxes copied from the Naturalist originals (naturalist 2.0.7 NaturalistEntityTypes).
    public static final DeferredHolder<EntityType<?>, EntityType<PetLion>> PET_LION =
            pet("pet_lion", PetLion::new, 1.5F, 1.8F);
    public static final DeferredHolder<EntityType<?>, EntityType<PetTiger>> PET_TIGER =
            pet("pet_tiger", PetTiger::new, 1.5F, 1.8F);
    public static final DeferredHolder<EntityType<?>, EntityType<PetBear>> PET_BEAR =
            pet("pet_bear", PetBear::new, 1.4F, 1.7F);
    public static final DeferredHolder<EntityType<?>, EntityType<PetBlackBear>> PET_BLACK_BEAR =
            pet("pet_black_bear", PetBlackBear::new, 1.3F, 1.3F);
    public static final DeferredHolder<EntityType<?>, EntityType<PetHippo>> PET_HIPPO =
            pet("pet_hippo", PetHippo::new, 1.8F, 1.8F);
    public static final DeferredHolder<EntityType<?>, EntityType<PetElephant>> PET_ELEPHANT =
            pet("pet_elephant", PetElephant::new, 2.5F, 3.5F);
    public static final DeferredHolder<EntityType<?>, EntityType<PetMammoth>> PET_MAMMOTH =
            pet("pet_mammoth", PetMammoth::new, 2.5F, 4.0F);
    public static final DeferredHolder<EntityType<?>, EntityType<PetGiraffe>> PET_GIRAFFE =
            pet("pet_giraffe", PetGiraffe::new, 1.9F, 5.4F);
    public static final DeferredHolder<EntityType<?>, EntityType<PetOstrich>> PET_OSTRICH =
            pet("pet_ostrich", PetOstrich::new, 1.2F, 2.5F);
    public static final DeferredHolder<EntityType<?>, EntityType<PetVulture>> PET_VULTURE =
            pet("pet_vulture", PetVulture::new, 0.9F, 0.5F);

    // Boss entities (spawned only at boss points, never naturally): same hitboxes as the originals, scaled per entity.
    public static final DeferredHolder<EntityType<?>, EntityType<BossLion>> BOSS_LION =
            pet("boss_lion", BossLion::new, 1.5F, 1.8F);
    public static final DeferredHolder<EntityType<?>, EntityType<BossTiger>> BOSS_TIGER =
            pet("boss_tiger", BossTiger::new, 1.5F, 1.8F);
    public static final DeferredHolder<EntityType<?>, EntityType<BossBear>> BOSS_BEAR =
            pet("boss_bear", BossBear::new, 1.4F, 1.7F);
    public static final DeferredHolder<EntityType<?>, EntityType<BossElephant>> BOSS_ELEPHANT =
            pet("boss_elephant", BossElephant::new, 2.5F, 3.5F);
    public static final DeferredHolder<EntityType<?>, EntityType<BossMammoth>> BOSS_MAMMOTH =
            pet("boss_mammoth", BossMammoth::new, 2.5F, 4.0F);
    public static final DeferredHolder<EntityType<?>, EntityType<BossVulture>> BOSS_VULTURE =
            pet("boss_vulture", BossVulture::new, 0.9F, 0.5F);
    /** All boss types (command suggestions). */
    public static final List<DeferredHolder<EntityType<?>, ? extends EntityType<?>>> BOSS_TYPES =
            List.of(BOSS_LION, BOSS_TIGER, BOSS_BEAR, BOSS_ELEPHANT, BOSS_MAMMOTH, BOSS_VULTURE);

    public static final DeferredHolder<SoundEvent, SoundEvent> WHISTLE = sound("whistle");
    public static final DeferredHolder<SoundEvent, SoundEvent> CAPTURE_SUCCESS = sound("capture_success");
    public static final DeferredHolder<SoundEvent, SoundEvent> CAPTURE_FAIL = sound("capture_fail");

    private PetRegistry() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        DATA_COMPONENTS.register(modBus);
        ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        modBus.addListener(PetRegistry::onAttributes);
    }

    /**
     * Each pet and boss uses its Naturalist original's attributes; PetBehavior.applyStats overrides pet speed/attack
     * on spawn, BossBehavior.apply multiplies boss health/attack once.
     */
    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(PET_LION.get(), Lion.createAttributes().build());
        event.put(PET_TIGER.get(), Tiger.createAttributes().build());
        event.put(PET_BEAR.get(), Bear.createAttributes().build());
        event.put(PET_BLACK_BEAR.get(), BlackBear.createAttributes().build());
        event.put(PET_HIPPO.get(), Hippo.createAttributes().build());
        event.put(PET_ELEPHANT.get(), Elephant.createAttributes().build());
        event.put(PET_MAMMOTH.get(), Mammoth.createAttributes().build());
        event.put(PET_GIRAFFE.get(), Giraffe.createAttributes().build());
        event.put(PET_OSTRICH.get(), Ostrich.createAttributes().build());
        event.put(PET_VULTURE.get(), Vulture.createAttributes().build());
        event.put(BOSS_LION.get(), Lion.createAttributes().build());
        event.put(BOSS_TIGER.get(), Tiger.createAttributes().build());
        event.put(BOSS_BEAR.get(), Bear.createAttributes().build());
        event.put(BOSS_ELEPHANT.get(), Elephant.createAttributes().build());
        event.put(BOSS_MAMMOTH.get(), Mammoth.createAttributes().build());
        event.put(BOSS_VULTURE.get(), Vulture.createAttributes().build());
    }

    private static <T extends Mob> DeferredHolder<EntityType<?>, EntityType<T>> pet(
            String name, EntityType.EntityFactory<T> factory, float width, float height) {
        return ENTITIES.register(name, () -> EntityType.Builder.of(factory, MobCategory.CREATURE)
                .sized(width, height).clientTrackingRange(10).build(name));
    }

    private static DeferredItem<CaptureBallItem> ball(String name, CaptureTier tier) {
        return ITEMS.registerItem("capture_ball_" + name, p -> new CaptureBallItem(tier, p),
                new Item.Properties().stacksTo(16));
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(GettCore.id(name)));
    }
}
