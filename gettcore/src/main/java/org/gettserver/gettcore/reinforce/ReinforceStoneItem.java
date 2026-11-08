package org.gettserver.gettcore.reinforce;

import net.minecraft.world.item.Item;

/** 강화석. 아무 동작 없는 재료 아이템이며 강화 화면에서 단계별 개수만큼 소모된다. */
public class ReinforceStoneItem extends Item {
    public ReinforceStoneItem(Properties properties) {
        super(properties);
    }
}
