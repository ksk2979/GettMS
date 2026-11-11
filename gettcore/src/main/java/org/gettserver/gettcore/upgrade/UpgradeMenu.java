package org.gettserver.gettcore.upgrade;

import java.util.Optional;
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
import org.gettserver.gettcore.data.GettData;

/**
 * 업그레이드 메뉴: 장비 1칸 + 플레이어 인벤토리. 재료·골드는 인벤토리/잔액에서 자동으로 센다. 판정은 서버의
 * {@link UpgradeService#apply}. 장비 칸에는 upgrade.json 단계에 나오는 아이템(마지막 등급 포함)만 들어간다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public class UpgradeMenu extends AbstractContainerMenu {
    public static final int GEAR_SLOT = 0;
    public static final int GEAR_X = 8;
    public static final int GEAR_Y = 18;
    public static final int INV_X = 150;
    public static final int INV_Y = 62;

    private static final int INV_START = 1;
    private static final int HOTBAR_START = INV_START + 27;
    private static final int INV_END = HOTBAR_START + 9;

    private final Container container = new SimpleContainer(1);
    private final boolean clientSide;

    /** Client constructor (menu type factory); the buffer carries nothing. */
    public UpgradeMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv);
    }

    public UpgradeMenu(int id, Inventory inv) {
        super(GettCore.UPGRADE_MENU.get(), id);
        clientSide = inv.player.level().isClientSide();
        addSlot(new Slot(container, GEAR_SLOT, GEAR_X, GEAR_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return accepts(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y + 58));
        }
    }

    /** This side's copy of upgrade.json (server: GettData, client: the synced copy). */
    public UpgradeConfig config() {
        return clientSide ? ClientUpgradeConfig.get() : GettData.get().upgrade();
    }

    public ItemStack gear() {
        return container.getItem(GEAR_SLOT);
    }

    /** Puts the upgraded stack in the gear slot (server, after the cost was taken). */
    void setGear(ItemStack stack) {
        container.setItem(GEAR_SLOT, stack);
    }

    private boolean accepts(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (BackpackBridge.isBackpack(stack)) return !config().backpackTiers().isEmpty();
        return config().involves(UpgradeService.key(stack));
    }

    /** 다음 단계가 있고 재료 {@code held}개·골드 {@code gold}가 비용 이상. */
    public boolean canApply(int held, long gold) {
        Optional<UpgradeConfig.Step> step = UpgradeService.nextStep(gear(), config());
        return UpgradeRules.canUpgrade(step, held, gold);
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer) {
            clearContainer(player, container);
        }
    }

    /** 장비 칸의 아이템을 인벤토리로 옮긴다(넘치는 것만 발밑에 떨군다). 서버 전용. */
    public void returnItems(ServerPlayer player) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.removeItemNoUpdate(i);
            if (!stack.isEmpty()) player.getInventory().placeItemBackInInventory(stack);
        }
    }

    /** 접속 종료: 저장 전에 인벤토리로 옮긴다(강화 메뉴와 같은 이유). */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.containerMenu instanceof UpgradeMenu menu) {
            menu.returnItems(player);
        }
    }

    /** 사망: 인벤토리로 옮겨 두면 keepInventory 등 인벤토리 규칙을 그대로 따른다. 취소된 사망은 받지 않는다. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.containerMenu instanceof UpgradeMenu menu) {
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
        } else if (accepts(stack)) {
            moved = moveItemStackTo(stack, GEAR_SLOT, GEAR_SLOT + 1, false);
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
