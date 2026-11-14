package org.gettserver.gettcore.enchant.effects;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.SpecialEnchants;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;

/** 괭이 특수 능력: 수확(주변 다 자란 작물 함께 수확), 풍작(드롭 추가), 재심기(씨앗 1개로 다시 심기). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class HoeEffects {
    private HoeEffects() {}

    private static boolean grownCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    /**
     * 재심기 괭이를 들고 있으면 덜 자란 작물은 부서지지 않는다. 작물은 한 번에 부서지는 블록이라, 왼클릭을
     * 누르고 있으면 다음 틱에 다시 심긴 싹이 곧바로 또 부서지기 때문. 클라이언트에서도 판정해야 하므로
     * (서버만 막으면 싹이 깜빡인다) 서버 전용인 {@link EffectContext#activeLevel}을 쓰지 않는다.
     */
    private static boolean protectsSprout(Player player, BlockState state) {
        if (!(state.getBlock() instanceof CropBlock crop) || crop.isMaxAge(state)) return false;
        ItemStack tool = player.getMainHandItem();
        return SpecialEnchants.toolOf(tool).filter(SpecialEnchants.Tool.HOE::equals).isPresent()
                && SpecialEnchants.level(tool, Ability.REPLANT, player.registryAccess()) > 0;
    }

    /** 클라이언트·서버 양쪽에서 받는다: 클라이언트가 파괴를 예측하지 않게 하고, 서버도 파괴를 시작하지 않는다. */
    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (protectsSprout(event.getEntity(), event.getLevel().getBlockState(event.getPos()))) {
            event.setCanceled(true);
        }
    }

    /** 수확: 보호 모드가 먼저 취소할 수 있도록 가장 늦게 받는다(취소된 이벤트는 오지 않는다). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        // 모드가 없는 클라이언트 등 왼클릭 취소를 지나친 파괴도 서버에서 막는다
        if (protectsSprout(event.getPlayer(), event.getState())) {
            event.setCanceled(true);
            return;
        }
        if (EffectContext.inReentrant()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        if (!grownCrop(state)) return;
        int lvl = EffectContext.activeLevel(player, Ability.HARVEST);
        if (lvl <= 0) return;
        int radius = (int) GettData.get().enchant().harvest().get(lvl);
        BlockPos center = event.getPos();
        Block block = state.getBlock();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                // 도구가 사라졌거나 내구도가 바닥이면 중단. 한 칸 실패(보호 구역 등)는 건너뛴다.
                if (!EffectContext.toolUsable(player)) return;
                BlockPos pos = center.offset(dx, 0, dz);
                BlockState other = level.getBlockState(pos);
                if (other.is(block) && grownCrop(other)) EffectContext.breakExtra(player, pos);
            }
        }
    }

    /** 풍작·재심기: 수확이 추가로 부순 작물에도 적용된다(재귀 플래그를 보지 않는다). */
    @SubscribeEvent
    public static void onDrops(BlockDropsEvent event) {
        if (!(event.getBreaker() instanceof Player player)) return;
        BlockState state = event.getState();
        if (!grownCrop(state)) return;
        CropBlock crop = (CropBlock) state.getBlock();
        ServerLevel level = event.getLevel();
        BlockPos pos = event.getPos();
        // getTool()은 파괴 직전 메인핸드의 복사본: 도구가 이번 파괴로 부서져도 판정할 수 있다.
        ItemStack tool = event.getTool();
        List<ItemEntity> drops = event.getDrops();
        Item seed = crop.getCloneItemStack(level, pos, state).getItem();

        int bounty = EffectContext.activeLevel(player, Ability.BOUNTY, tool);
        if (bounty > 0 && !drops.isEmpty()
                && EffectContext.chance(GettData.get().enchant().bounty().get(bounty), level.getRandom())) {
            ItemEntity source = drops.get(0);
            for (ItemEntity e : drops) {
                if (!isSeed(e.getItem(), seed)) {
                    source = e;
                    break;
                }
            }
            ItemEntity extra = new ItemEntity(level, source.getX(), source.getY(), source.getZ(),
                    source.getItem().copyWithCount(1));
            extra.setDefaultPickUpDelay();
            drops.add(extra);
        }

        if (EffectContext.activeLevel(player, Ability.REPLANT, tool) > 0) {
            replant(level, pos, crop, seed, drops, player.getInventory());
        }
    }

    private static boolean isSeed(ItemStack stack, Item seed) {
        return stack.is(seed) || stack.is(Tags.Items.SEEDS);
    }

    private static void replant(ServerLevel level, BlockPos pos, CropBlock crop, Item seed, List<ItemEntity> drops,
                                Inventory inventory) {
        if (!takeSeed(seed, drops, inventory)) return;
        BlockPos at = pos.immutable();
        MinecraftServer server = level.getServer();
        // server.execute는 서버 스레드에서 호출하면 곧바로 실행되므로 TickTask로 큐에 넣어 다음 틱에 심는다.
        server.tell(new TickTask(server.getTickCount() + 1, () -> {
            BlockState planted = crop.defaultBlockState();
            if (level.getBlockState(at).isAir() && planted.canSurvive(level, at)) {
                level.setBlock(at, planted, Block.UPDATE_ALL);
            } else {
                Block.popResource(level, at, new ItemStack(seed)); // 심을 수 없게 됐으면 씨앗을 돌려준다
            }
        }));
    }

    /** 드롭에서 씨앗 1개를 빼고, 없으면 인벤토리에서 1개를 뺀다. 둘 다 없으면 false. */
    private static boolean takeSeed(Item seed, List<ItemEntity> drops, Inventory inventory) {
        for (int i = 0; i < drops.size(); i++) {
            ItemEntity entity = drops.get(i);
            ItemStack stack = entity.getItem();
            if (!stack.is(seed)) continue;
            if (stack.getCount() <= 1) {
                drops.remove(i);
            } else {
                entity.setItem(stack.copyWithCount(stack.getCount() - 1));
            }
            return true;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(seed)) {
                stack.shrink(1);
                inventory.setChanged();
                return true;
            }
        }
        return false;
    }
}
