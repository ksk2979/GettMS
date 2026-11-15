package org.gettserver.gettcore.farm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import org.gettserver.gettcore.GettCore;

/**
 * 작물이 떨어뜨리는 씨앗은 종류마다 1개까지. 씨앗은 {@code c:seeds} 태그 아이템이라, 당근·감자처럼 작물 자체를
 * 심는 것은 그대로다. 누가(플레이어·물·피스톤·주민) 부쉈든 적용되고, 행운도 씨앗 수를 늘리지 못한다. 괭이의
 * 풍작·재심기({@code HoeEffects})보다 먼저 돌아서 재심기는 이 1개를 쓴다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class SeedDrops {
    private SeedDrops() {}

    static boolean isCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock || state.is(BlockTags.CROPS);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDrops(BlockDropsEvent event) {
        if (!isCrop(event.getState())) return;
        List<ItemEntity> drops = event.getDrops();
        List<Item> seeds = new ArrayList<>(drops.size());
        int[] counts = new int[drops.size()];
        for (int i = 0; i < counts.length; i++) {
            ItemStack stack = drops.get(i).getItem();
            seeds.add(stack.is(Tags.Items.SEEDS) ? stack.getItem() : null);
            counts[i] = stack.getCount();
        }
        int[] capped = capped(seeds, counts);
        for (int i = counts.length - 1; i >= 0; i--) {
            if (capped[i] == counts[i]) continue;
            if (capped[i] <= 0) {
                drops.remove(i);
            } else {
                ItemEntity entity = drops.get(i);
                entity.setItem(entity.getItem().copyWithCount(capped[i]));
            }
        }
    }

    /**
     * New stack sizes for one block's drops: {@code seeds.get(i)} is the seed kind of stack i (null = not a seed).
     * The first stack of each seed kind becomes 1, later stacks of that kind become 0 (removed); the rest keep
     * {@code counts[i]}.
     */
    static <K> int[] capped(List<K> seeds, int[] counts) {
        int[] out = counts.clone();
        Set<K> seen = new HashSet<>();
        for (int i = 0; i < out.length; i++) {
            K kind = seeds.get(i);
            if (kind == null || out[i] <= 0) continue;
            out[i] = seen.add(kind) ? 1 : 0;
        }
        return out;
    }
}
