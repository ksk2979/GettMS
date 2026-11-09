package org.gettserver.gettcore.invsee;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.gettserver.gettcore.invsee.InvSeeLayout.Kind;

/**
 * Server-side 9x5 menu showing another player's inventory. The slot layout is exactly the vanilla
 * {@link ChestMenu} 9x5 layout (45 container slots, then the viewer's 27 storage + 9 hotbar), so the
 * vanilla client screen renders it. Container slots read and write the target's {@link Inventory}
 * directly (see {@link InvSeeLayout}); the target's own menu re-syncs every tick from the same stacks.
 */
public final class InvSeeMenu extends AbstractContainerMenu {
    private static final int ROWS = 5;
    private final ServerPlayer target;
    private final boolean self;

    public InvSeeMenu(int id, Inventory viewerInv, ServerPlayer target) {
        super(MenuType.GENERIC_9x5, id);
        this.target = target;
        this.self = viewerInv.player == target;
        Inventory inv = target.getInventory();
        Container filler = fillerContainer();
        int fillerIndex = 0;
        for (int s = 0; s < InvSeeLayout.SIZE; s++) {
            int x = 8 + (s % 9) * 18;
            int y = 18 + (s / 9) * 18;
            Slot slot = switch (InvSeeLayout.kind(s)) {
                case MAIN, OFFHAND -> new Slot(inv, InvSeeLayout.inventoryIndex(s), x, y);
                case ARMOR -> new ArmorSlot(inv, InvSeeLayout.inventoryIndex(s), x, y, InvSeeLayout.armorSlot(s));
                case FILLER -> new FillerSlot(filler, fillerIndex++, x, y);
            };
            addSlot(slot);
        }
        // Same coordinates as ChestMenu so the client and server agree on every slot.
        int yOff = (ROWS - 4) * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(viewerInv, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + yOff));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(viewerInv, col, 8 + col * 18, 161 + yOff));
    }

    private static Container fillerContainer() {
        SimpleContainer c = new SimpleContainer(InvSeeLayout.SIZE - 41);
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            pane.set(DataComponents.CUSTOM_NAME, Component.literal("-"));
            c.setItem(i, pane);
        }
        return c;
    }

    private static boolean isFiller(int slotId) {
        return slotId >= 0 && slotId < InvSeeLayout.SIZE && InvSeeLayout.kind(slotId) == Kind.FILLER;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // Fillers ignore every kind of click (pickup, swap, throw, clone, drag); the client is re-synced.
        if (isFiller(slotId)) return;
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Viewing yourself, the top and bottom halves are the same stacks; moving between them could merge a
        // stack into itself and duplicate it, so shift-click is off there (normal clicks still work).
        if (self || isFiller(index)) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index < InvSeeLayout.SIZE) {
            moveItemStackTo(stack, InvSeeLayout.SIZE, slots.size(), true);
        } else {
            for (int[] range : InvSeeLayout.QUICK_MOVE_INTO_TARGET) {
                if (stack.isEmpty()) break;
                moveItemStackTo(stack, range[0], range[1], false);
            }
        }
        if (stack.getCount() == before.getCount()) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        return before;
    }

    @Override
    public boolean stillValid(Player player) {
        return isOnline(target);
    }

    /** The target is still connected and is the same entity (not respawned or relogged). */
    static boolean isOnline(ServerPlayer target) {
        return !target.isRemoved() && !target.hasDisconnected()
                && target.server.getPlayerList().getPlayer(target.getUUID()) == target;
    }

    /** Inert placeholder: never accepts or gives items. */
    static final class FillerSlot extends Slot {
        FillerSlot(Container c, int index, int x, int y) {
            super(c, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    /** One armor piece; only items that can be worn in that slot go in. */
    final class ArmorSlot extends Slot {
        private final EquipmentSlot equip;

        ArmorSlot(Container c, int index, int x, int y, EquipmentSlot equip) {
            super(c, index, x, y);
            this.equip = equip;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.canEquip(equip, target);
        }
    }

    /** Vanilla 9x3 chest menu over the target's ender chest that stays open while the target is online. */
    public static ChestMenu ender(int id, Inventory viewerInv, ServerPlayer target) {
        return new ChestMenu(MenuType.GENERIC_9x3, id, viewerInv, target.getEnderChestInventory(), 3) {
            @Override
            public boolean stillValid(Player player) {
                return isOnline(target);
            }
        };
    }
}
