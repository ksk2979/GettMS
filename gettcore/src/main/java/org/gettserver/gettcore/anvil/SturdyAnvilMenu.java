package org.gettserver.gettcore.anvil;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;

/** 튼튼한 모루에서 연 모루 메뉴(서버 쪽 표시용). 클라이언트는 바닐라 모루 화면 그대로. */
public class SturdyAnvilMenu extends AnvilMenu {
    public SturdyAnvilMenu(int id, Inventory inv, ContainerLevelAccess access) {
        super(id, inv, access);
    }
}
