package org.gettserver.gettcore.enchant;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.shop.ShopMenu;

/** 마법부여 메뉴: 도구 1칸 + 부여석 1칸 + 플레이어 인벤토리. 부여 판정은 서버의 {@link EnchantService#apply}. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public class EnchantMenu extends AbstractContainerMenu {
    public static final int TOOL_SLOT = 0;
    public static final int STONE_SLOT = 1;
    public static final int TOOL_X = 44;
    public static final int TOOL_Y = 60;
    public static final int STONE_X = 118;
    public static final int STONE_Y = 92;

    private static final int INV_START = 2;
    private static final int HOTBAR_START = INV_START + 27;
    private static final int INV_END = HOTBAR_START + 9;

    private final Container container = new SimpleContainer(2);

    /** Client constructor (menu type factory); the buffer carries nothing. */
    public EnchantMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv);
    }

    public EnchantMenu(int id, Inventory inv) {
        super(GettCore.ENCHANT_MENU.get(), id);
        addSlot(new Slot(container, TOOL_SLOT, TOOL_X, TOOL_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return SpecialEnchants.toolOf(stack).isPresent();
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        addSlot(new Slot(container, STONE_SLOT, STONE_X, STONE_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(GettCore.ENCHANT_STONE.get());
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, ShopMenu.INV_X + col * 18, ShopMenu.INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, ShopMenu.INV_X + col * 18, ShopMenu.INV_Y + 58));
        }
    }

    public ItemStack tool() {
        return container.getItem(TOOL_SLOT);
    }

    public ItemStack stone() {
        return container.getItem(STONE_SLOT);
    }

    /** 도구 칸에 지원 도구, 부여석 칸에 부여석이 1개 이상. */
    public boolean canApply() {
        return SpecialEnchants.toolOf(tool()).isPresent() && stone().is(GettCore.ENCHANT_STONE.get());
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Same as the crafting table: both slots go back to the inventory (dropped if the player is gone).
        if (player instanceof ServerPlayer) {
            clearContainer(player, container);
        }
    }

    /** 두 칸의 아이템을 인벤토리로 옮긴다(넘치는 것만 발밑에 떨군다). 서버 전용. */
    public void returnItems(ServerPlayer player) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.removeItemNoUpdate(i);
            if (!stack.isEmpty()) player.getInventory().placeItemBackInInventory(stack);
        }
    }

    /**
     * 접속 종료·서버 종료: PlayerList.remove가 플레이어를 저장한 뒤에야 메뉴가 닫히고(그때는 이미 접속이 끊겨
     * clearContainer가 바닥에 떨군다), 이 이벤트는 저장 전에 온다. 여기서 인벤토리로 옮겨 함께 저장되게 한다.
     */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.containerMenu instanceof EnchantMenu menu) {
            menu.returnItems(player);
        }
    }

    /** 사망: 인벤토리로 옮겨 두면 keepInventory·시체 처리 등 인벤토리 규칙을 그대로 따른다. 취소된 사망은 받지 않는다. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.containerMenu instanceof EnchantMenu menu) {
            menu.returnItems(player);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean moved;
        if (index < INV_START) {
            moved = moveItemStackTo(stack, INV_START, INV_END, true);
        } else if (stack.is(GettCore.ENCHANT_STONE.get())) {
            moved = moveItemStackTo(stack, STONE_SLOT, STONE_SLOT + 1, false);
        } else if (SpecialEnchants.toolOf(stack).isPresent()) {
            moved = moveItemStackTo(stack, TOOL_SLOT, TOOL_SLOT + 1, false);
        } else if (index < HOTBAR_START) {
            moved = moveItemStackTo(stack, HOTBAR_START, INV_END, false);
        } else {
            moved = moveItemStackTo(stack, INV_START, HOTBAR_START, false);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == copy.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return copy;
    }
}
