package org.gettserver.gettcore.shop;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.GettCore;

public class ShopMenu extends AbstractContainerMenu {
    public static final int INV_X = 180;
    public static final int INV_Y = 84;

    private final String shopId;
    private final ShopMode mode;
    private final boolean canEdit;

    public ShopMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readUtf(), buf.readEnum(ShopMode.class), buf.readBoolean());
    }

    public ShopMenu(int id, Inventory inv, String shopId, ShopMode mode, boolean canEdit) {
        super(GettCore.SHOP_MENU.get(), id);
        this.shopId = shopId;
        this.mode = mode;
        this.canEdit = canEdit;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y + 58));
        }
    }

    public String shopId() { return shopId; }
    public ShopMode mode() { return mode; }
    public boolean canEdit() { return canEdit; }

    @Override
    public boolean stillValid(Player player) {
        return !(player instanceof ServerPlayer sp) || ShopService.isOpen(sp, shopId);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer sp) {
            ShopService.closed(sp);
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
        boolean moved = index < 27
                ? moveItemStackTo(stack, 27, 36, false)
                : moveItemStackTo(stack, 0, 27, false);
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
