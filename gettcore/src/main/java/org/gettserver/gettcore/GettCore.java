package org.gettserver.gettcore;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.gettserver.gettcore.detector.DetectingEffect;
import org.gettserver.gettcore.detector.DetectorItem;
import org.gettserver.gettcore.economy.CheckItem;
import org.gettserver.gettcore.enchant.EnchantMenu;
import org.gettserver.gettcore.enchant.EnchantService;
import org.gettserver.gettcore.enchant.EnchantStoneItem;
import org.gettserver.gettcore.item.ReturnStoneItem;
import org.gettserver.gettcore.item.XpOrbItem;
import org.gettserver.gettcore.network.GettNetwork;
import org.gettserver.gettcore.pet.PetRegistry;
import org.gettserver.gettcore.pet.PetService;
import org.gettserver.gettcore.reinforce.NtglBridge;
import org.gettserver.gettcore.reinforce.ReinforceConfig;
import org.gettserver.gettcore.reinforce.ReinforceEffects;
import org.gettserver.gettcore.reinforce.ReinforceMenu;
import org.gettserver.gettcore.reinforce.ReinforceService;
import org.gettserver.gettcore.reinforce.ReinforceStoneItem;
import org.gettserver.gettcore.shop.ShopMenu;
import org.gettserver.gettcore.shop.ShopService;

@Mod(GettCore.MOD_ID)
public class GettCore {
    public static final String MOD_ID = "gettcore";
    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);
    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, MOD_ID);

    /** 탐지 표시가 남은 시간을 HUD 효과 목록에 보여 주기 위한 표시용 효과(동작 없음). */
    public static final DeferredHolder<MobEffect, MobEffect> DETECTING =
            MOB_EFFECTS.register("detecting", DetectingEffect::new);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> CHECK_AMOUNT =
            DATA_COMPONENTS.registerComponentType("check_amount",
                    b -> b.persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> CHECK_ISSUER =
            DATA_COMPONENTS.registerComponentType("check_issuer",
                    b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
    /** 장비 강화 단계. 0 단계는 컴포넌트 없음. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> REINFORCE_LEVEL =
            DATA_COMPONENTS.registerComponentType("reinforce_level",
                    b -> b.persistent(ExtraCodecs.NON_NEGATIVE_INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    /** 탐지기의 대상 칸(아이템 1개). 없으면 컴포넌트 없음. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> DETECTOR_TARGET =
            DATA_COMPONENTS.registerComponentType("detector_target",
                    b -> b.persistent(ItemContainerContents.CODEC).networkSynchronized(ItemContainerContents.STREAM_CODEC));

    public static final DeferredItem<CheckItem> CHECK =
            ITEMS.registerItem("check", CheckItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredItem<XpOrbItem> XP_ORB =
            ITEMS.registerItem("xp_orb_item", XpOrbItem::new, new Item.Properties());
    public static final DeferredItem<ReturnStoneItem> RETURN_STONE =
            ITEMS.registerItem("return_stone", ReturnStoneItem::new, new Item.Properties().stacksTo(16));
    public static final DeferredItem<EnchantStoneItem> ENCHANT_STONE =
            ITEMS.registerItem("enchant_stone", EnchantStoneItem::new, new Item.Properties().stacksTo(64));
    public static final DeferredItem<ReinforceStoneItem> REINFORCE_STONE =
            ITEMS.registerItem("reinforce_stone", ReinforceStoneItem::new, new Item.Properties().stacksTo(64));
    public static final DeferredItem<DetectorItem> DETECTOR =
            ITEMS.registerItem("detector", DetectorItem::new, new Item.Properties().stacksTo(1));

    /** 튼튼한 모루: 써도 금이 가지 않고 떨어지지 않음. 관리자 지급 전용(크리에이티브 탭·/give). */
    public static final net.neoforged.neoforge.registries.DeferredBlock<org.gettserver.gettcore.anvil.SturdyAnvilBlock> STURDY_ANVIL =
            BLOCKS.registerBlock("sturdy_anvil", org.gettserver.gettcore.anvil.SturdyAnvilBlock::new,
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.ANVIL));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> STURDY_ANVIL_ITEM =
            ITEMS.registerSimpleBlockItem(STURDY_ANVIL);

    public static final DeferredHolder<MenuType<?>, MenuType<ShopMenu>> SHOP_MENU =
            MENUS.register("shop", () -> IMenuTypeExtension.create(ShopMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<EnchantMenu>> ENCHANT_MENU =
            MENUS.register("enchant", () -> IMenuTypeExtension.create(EnchantMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<ReinforceMenu>> REINFORCE_MENU =
            MENUS.register("reinforce", () -> IMenuTypeExtension.create(ReinforceMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<org.gettserver.gettcore.upgrade.UpgradeMenu>> UPGRADE_MENU =
            MENUS.register("upgrade", () -> IMenuTypeExtension.create(org.gettserver.gettcore.upgrade.UpgradeMenu::new));

    // 크리에이티브 인벤토리 전용 탭. 수표는 금액(0) 없는 빈 수표가 되므로 넣지 않는다.
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GETT_TAB =
            CREATIVE_TABS.register("gett", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.gettcore"))
                    .icon(() -> new ItemStack(RETURN_STONE.get()))
                    .displayItems((params, output) -> {
                        output.accept(XP_ORB.get());
                        output.accept(RETURN_STONE.get());
                        output.accept(ENCHANT_STONE.get());
                        output.accept(REINFORCE_STONE.get());
                        output.accept(DETECTOR.get());
                        output.accept(STURDY_ANVIL_ITEM.get());
                        output.accept(PetRegistry.CAPTURE_BALL_IRON.get());
                        output.accept(PetRegistry.CAPTURE_BALL_GOLD.get());
                        output.accept(PetRegistry.CAPTURE_BALL_DIAMOND.get());
                        output.accept(PetRegistry.CAPTURE_BALL_NETHERITE.get());
                        org.gettserver.gettcore.pet.boss.BossEggs.ITEMS.forEach(egg -> output.accept(egg.get()));
                    })
                    .build());

    public GettCore(IEventBus modBus, ModContainer container) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);
        MENUS.register(modBus);
        DATA_COMPONENTS.register(modBus);
        MOB_EFFECTS.register(modBus);
        PetRegistry.register(modBus);
        org.gettserver.gettcore.pet.command.CommandArgumentTypes.register(modBus);
        org.gettserver.gettcore.balance.ScaleSpawnsModifier.register(modBus);
        modBus.addListener(GettNetwork::register);
        // 특수 능력 책은 크리에이티브 탭에서 뺀다(바닐라 책 생성 뒤에 돌도록 LOWEST).
        modBus.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                org.gettserver.gettcore.enchant.SpecialBookFilter::onBuildTabs);
        ShopService.registerReloadHook();
        EnchantService.registerReloadHook();
        ReinforceService.registerReloadHook();
        org.gettserver.gettcore.upgrade.UpgradeService.registerReloadHook();
        ReinforceEffects.registerReloadHook();
        org.gettserver.gettcore.equipment.EquipmentEffects.registerReloadHook();
        // 단계 능력 효과 id 검사(등록됨 && 즉시 효과 아님): 레지스트리 조회는 sanitize 때(로드 이후)에만 일어난다.
        ReinforceConfig.effectUsable = id -> BuiltInRegistries.MOB_EFFECT.getOptional(id)
                .map(e -> !e.isInstantenous()).orElse(false);
        // NTGL(총) 은 선택 의존성: NtglBridge 는 이 분기 안에서만 참조해야 NTGL 없는 환경에서 클래스가 로드되지 않는다.
        // 미래 NTGL 이 API 를 바꿔 링크가 깨지면 총 보너스만 잃고 기동은 계속한다.
        if (ModList.get().isLoaded("ntgl")) {
            try {
                NtglBridge.install();
            } catch (LinkageError e) {
                LOGGER.warn("gettcore: NTGL 연동 비활성 (호환되지 않는 NTGL 버전?): {}", e.toString());
            }
        }
        // Curios(등 칸 겉날개) 도 선택 의존성: CuriosBridge 는 이 분기 안에서만 참조.
        if (ModList.get().isLoaded("curios")) {
            try {
                org.gettserver.gettcore.reinforce.CuriosBridge.install();
            } catch (LinkageError e) {
                LOGGER.warn("gettcore: Curios 연동 비활성 (호환되지 않는 Curios 버전?): {}", e.toString());
            }
        }
        // Dream Displays(화면) 도 선택 의존성: DisplayService 는 이 분기 안에서만 참조.
        if (ModList.get().isLoaded("dreamdisplays")) {
            try {
                org.gettserver.gettcore.display.DisplayService.install();
            } catch (LinkageError e) {
                LOGGER.warn("gettcore: Dream Displays 연동 비활성 (호환되지 않는 버전?): {}", e.toString());
            }
        }
        PetService.registerReloadHook();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
