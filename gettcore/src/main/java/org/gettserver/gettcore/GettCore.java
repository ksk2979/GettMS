package org.gettserver.gettcore;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.gettserver.gettcore.economy.CheckItem;
import org.gettserver.gettcore.enchant.EnchantMenu;
import org.gettserver.gettcore.enchant.EnchantService;
import org.gettserver.gettcore.enchant.EnchantStoneItem;
import org.gettserver.gettcore.item.ReturnStoneItem;
import org.gettserver.gettcore.item.XpOrbItem;
import org.gettserver.gettcore.network.GettNetwork;
import org.gettserver.gettcore.pet.PetRegistry;
import org.gettserver.gettcore.shop.ShopMenu;
import org.gettserver.gettcore.shop.ShopService;

@Mod(GettCore.MOD_ID)
public class GettCore {
    public static final String MOD_ID = "gettcore";
    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> CHECK_AMOUNT =
            DATA_COMPONENTS.registerComponentType("check_amount",
                    b -> b.persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> CHECK_ISSUER =
            DATA_COMPONENTS.registerComponentType("check_issuer",
                    b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredItem<CheckItem> CHECK =
            ITEMS.registerItem("check", CheckItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredItem<XpOrbItem> XP_ORB =
            ITEMS.registerItem("xp_orb_item", XpOrbItem::new, new Item.Properties());
    public static final DeferredItem<ReturnStoneItem> RETURN_STONE =
            ITEMS.registerItem("return_stone", ReturnStoneItem::new, new Item.Properties().stacksTo(16));
    public static final DeferredItem<EnchantStoneItem> ENCHANT_STONE =
            ITEMS.registerItem("enchant_stone", EnchantStoneItem::new, new Item.Properties().stacksTo(64));

    public static final DeferredHolder<MenuType<?>, MenuType<ShopMenu>> SHOP_MENU =
            MENUS.register("shop", () -> IMenuTypeExtension.create(ShopMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<EnchantMenu>> ENCHANT_MENU =
            MENUS.register("enchant", () -> IMenuTypeExtension.create(EnchantMenu::new));

    // 크리에이티브 인벤토리 전용 탭. 수표는 금액(0) 없는 빈 수표가 되므로 넣지 않는다.
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GETT_TAB =
            CREATIVE_TABS.register("gett", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.gettcore"))
                    .icon(() -> new ItemStack(RETURN_STONE.get()))
                    .displayItems((params, output) -> {
                        output.accept(XP_ORB.get());
                        output.accept(RETURN_STONE.get());
                        output.accept(ENCHANT_STONE.get());
                        output.accept(PetRegistry.CAPTURE_BALL_IRON.get());
                        output.accept(PetRegistry.CAPTURE_BALL_GOLD.get());
                        output.accept(PetRegistry.CAPTURE_BALL_DIAMOND.get());
                        output.accept(PetRegistry.CAPTURE_BALL_NETHERITE.get());
                    })
                    .build());

    public GettCore(IEventBus modBus, ModContainer container) {
        ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);
        MENUS.register(modBus);
        DATA_COMPONENTS.register(modBus);
        PetRegistry.register(modBus);
        modBus.addListener(GettNetwork::register);
        ShopService.registerReloadHook();
        EnchantService.registerReloadHook();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
