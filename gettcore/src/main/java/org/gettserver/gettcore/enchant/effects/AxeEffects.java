package org.gettserver.gettcore.enchant.effects;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;

/** 도끼 특수 능력: 벌목(자연 나무 통째로), 벌목 재심기(묘목 1개로 다시 심기), 목재 보너스(통나무 드롭 추가). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class AxeEffects {
    private AxeEffects() {}

    /** 통나무 id → 그 나무의 묘목 id. 순수 함수. */
    public static final class SaplingMap {
        private static final Map<String, String> SPECIAL = Map.of("mangrove_log", "mangrove_propagule");
        private static final List<String> WOODS =
                List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry");

        private SaplingMap() {}

        public static Optional<ResourceLocation> saplingFor(ResourceLocation logId) {
            if (!ResourceLocation.DEFAULT_NAMESPACE.equals(logId.getNamespace())) return Optional.empty();
            String path = logId.getPath();
            String special = SPECIAL.get(path);
            if (special != null) return Optional.of(ResourceLocation.withDefaultNamespace(special));
            if (!path.endsWith("_log")) return Optional.empty();
            String wood = path.substring(0, path.length() - "_log".length());
            if (!WOODS.contains(wood)) return Optional.empty();
            return Optional.of(ResourceLocation.withDefaultNamespace(wood + "_sapling"));
        }
    }

    /** 껍질 벗긴 통나무와 {@code _wood} 변형을 뺀 통나무. */
    private static boolean naturalLog(BlockState state) {
        if (!state.is(BlockTags.LOGS) || state.is(Tags.Blocks.STRIPPED_LOGS)) return false;
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("_log") && !path.startsWith("stripped_");
    }

    /** 벌목: 보호 모드가 먼저 취소할 수 있도록 가장 늦게 받는다. 추가 파괴는 다시 퍼뜨리지 않는다. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (EffectContext.inReentrant()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        if (!naturalLog(state)) return;
        int lvl = EffectContext.activeLevel(player, Ability.TIMBER);
        if (lvl <= 0) return;
        Block block = state.getBlock();
        BlockPos start = event.getPos().immutable();
        int max = (int) GettData.get().enchant().timber().get(lvl);
        List<BlockPos> found = EffectContext.bfs(p -> level.getBlockState(p).is(block), start, max, true);
        if (found.isEmpty()) return;
        List<BlockPos> all = new ArrayList<>(found);
        all.add(start);
        // 자연 나무 판정: 잎이 하나도 안 붙어 있으면 통나무 건축물로 보고 아무것도 더 부수지 않는다.
        if (all.stream().noneMatch(p -> touchesLeaves(level, p))) return;
        for (BlockPos p : found) {
            if (!EffectContext.toolUsable(player)) return;
            if (level.getBlockState(p).is(block)) EffectContext.breakExtra(player, p); // 그사이 바뀌었으면 건너뜀
        }
    }

    /** 6방향 중 자연 잎(플레이어가 놓은 persistent 잎 제외)이 붙어 있으면 true. */
    private static boolean touchesLeaves(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState s = level.getBlockState(pos.relative(d));
            if (!s.is(BlockTags.LEAVES)) continue;
            if (s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT)) continue;
            return true;
        }
        return false;
    }

    /** 목재 보너스·벌목 재심기: 벌목이 추가로 부순 통나무에도 적용된다(재귀 플래그를 보지 않는다). */
    @SubscribeEvent
    public static void onDrops(BlockDropsEvent event) {
        if (!(event.getBreaker() instanceof Player player)) return;
        BlockState state = event.getState();
        if (!naturalLog(state)) return;
        ServerLevel level = event.getLevel();
        // getTool()은 파괴 직전 메인핸드의 복사본: 도구가 이번 파괴로 부서져도 판정할 수 있다.
        ItemStack tool = event.getTool();
        List<ItemEntity> drops = event.getDrops();

        int lumber = EffectContext.activeLevel(player, Ability.LUMBER, tool);
        if (lumber > 0) {
            double percent = GettData.get().enchant().lumber().get(lumber);
            for (ItemEntity entity : drops) {
                ItemStack stack = entity.getItem();
                if (stack.is(ItemTags.LOGS) && EffectContext.chance(percent, level.getRandom())) {
                    entity.setItem(stack.copyWithCount(stack.getCount() + 1));
                }
            }
        }

        if (EffectContext.activeLevel(player, Ability.RESAPLING, tool) > 0) {
            resapling(level, event.getPos().immutable(), state, drops, player.getInventory());
        }
    }

    private static void resapling(ServerLevel level, BlockPos pos, BlockState log, List<ItemEntity> drops,
                                  Inventory inventory) {
        if (!level.getBlockState(pos.below()).is(BlockTags.DIRT)) return;
        Optional<ResourceLocation> id = SaplingMap.saplingFor(BuiltInRegistries.BLOCK.getKey(log.getBlock()));
        if (id.isEmpty()) return;
        Block sapling = BuiltInRegistries.BLOCK.get(id.get());
        Item item = sapling.asItem();
        if (sapling == Blocks.AIR || item == Items.AIR) return;
        BlockState planted = sapling.defaultBlockState();
        // 드롭 시점에는 통나무가 이미 지워져 있다. 지금 심을 수 없는 자리면 묘목을 쓰지 않는다.
        if (!planted.canSurvive(level, pos)) return;
        if (!takeOne(item, drops, inventory)) return;
        MinecraftServer server = level.getServer();
        // 서버 스레드에서 server.execute는 즉시 실행되므로 TickTask로 다음 틱에 심는다(벌목 연쇄가 끝난 뒤).
        server.tell(new TickTask(server.getTickCount() + 1, () -> {
            if (level.getBlockState(pos).isAir() && planted.canSurvive(level, pos)) {
                level.setBlock(pos, planted, Block.UPDATE_ALL);
            } else {
                Block.popResource(level, pos, new ItemStack(item)); // 심을 수 없게 됐으면 돌려준다
            }
        }));
    }

    /** 드롭에서 1개를 빼고, 없으면 인벤토리에서 1개를 뺀다. 둘 다 없으면 false. */
    private static boolean takeOne(Item item, List<ItemEntity> drops, Inventory inventory) {
        for (int i = 0; i < drops.size(); i++) {
            ItemEntity entity = drops.get(i);
            ItemStack stack = entity.getItem();
            if (!stack.is(item)) continue;
            if (stack.getCount() <= 1) {
                drops.remove(i);
            } else {
                entity.setItem(stack.copyWithCount(stack.getCount() - 1));
            }
            return true;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) {
                stack.shrink(1);
                inventory.setChanged();
                return true;
            }
        }
        return false;
    }
}
