package org.gettserver.gettcore.pet;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.gettserver.gettcore.GettCore;

/** Registers for the pet system: capture balls, the captured-mob item and its components, the projectile, sounds. */
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

    public static final DeferredHolder<SoundEvent, SoundEvent> WHISTLE = sound("whistle");
    public static final DeferredHolder<SoundEvent, SoundEvent> CAPTURE_SUCCESS = sound("capture_success");
    public static final DeferredHolder<SoundEvent, SoundEvent> CAPTURE_FAIL = sound("capture_fail");

    private PetRegistry() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        DATA_COMPONENTS.register(modBus);
        ENTITIES.register(modBus);
        SOUNDS.register(modBus);
    }

    private static DeferredItem<CaptureBallItem> ball(String name, CaptureTier tier) {
        return ITEMS.registerItem("capture_ball_" + name, p -> new CaptureBallItem(tier, p),
                new Item.Properties().stacksTo(16));
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(GettCore.id(name)));
    }
}
