package org.gettserver.gettcore.enchant.effects;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;

/** 곡괭이 특수 능력: 광맥(이어진 같은 광석), 광역(바라보는 면 3×3), 제련(드롭을 화로 결과로). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class PickaxeEffects {
    private PickaxeEffects() {}

    /** 광맥·광역: 보호 모드가 먼저 취소할 수 있도록 가장 늦게 받는다. 추가 파괴는 다시 퍼뜨리지 않는다. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (EffectContext.inReentrant()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        BlockPos pos = event.getPos().immutable();

        int vein = EffectContext.activeLevel(player, Ability.VEIN);
        // 광맥: 도구 등급이 원래 광석을 캘 수 있을 때만(돌 곡괭이로 다이아 광맥을 없애지 않는다)
        if (vein > 0 && state.is(Tags.Blocks.ORES) && player.hasCorrectToolForDrops(state)) {
            vein(player, level, pos, state.getBlock(), vein);
        }

        if (EffectContext.activeLevel(player, Ability.AREA) > 0) area(player, level, pos, state);
    }

    private static void vein(ServerPlayer player, ServerLevel level, BlockPos pos, Block block, int lvl) {
        int max = (int) GettData.get().enchant().vein().get(lvl);
        List<BlockPos> found = EffectContext.bfs(p -> level.getBlockState(p).is(block), pos, max, true);
        for (BlockPos p : found) {
            if (!EffectContext.toolUsable(player)) return;
            BlockState other = level.getBlockState(p);
            // 그사이 바뀌었거나 도구가 캘 수 없는 블록이면 건너뜀
            if (other.is(block) && player.hasCorrectToolForDrops(other)) EffectContext.breakExtra(player, p);
        }
    }

    private static void area(ServerPlayer player, ServerLevel level, BlockPos center, BlockState origin) {
        ItemStack tool = player.getMainHandItem();
        // 원래 블록이 곡괭이로 캐는 단단한 블록일 때만(횃불·문·흙을 부쉈다고 주변 벽이 사라지지 않게)
        float originHardness = origin.getDestroySpeed(level, center);
        if (!(originHardness > 0) || !tool.isCorrectToolForDrops(origin)) return;
        Direction.Axis axis = faceAxis(player, center);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                if (!EffectContext.toolUsable(player)) return;
                BlockPos p = switch (axis) {
                    case X -> center.offset(0, a, b);
                    case Y -> center.offset(a, 0, b);
                    case Z -> center.offset(a, b, 0);
                };
                BlockState other = level.getBlockState(p);
                if (other.isAir() || other.getBlock() instanceof LiquidBlock) continue;
                if (other.getBlock() instanceof SpawnerBlock || other.getBlock() instanceof BuddingAmethystBlock) continue;
                float hardness = other.getDestroySpeed(level, p);
                if (hardness < 0 || hardness > originHardness || !tool.isCorrectToolForDrops(other)) continue;
                EffectContext.breakExtra(player, p);
            }
        }
    }

    /** 플레이어가 바라보는 블록 면의 축. 시선이 이 블록에 닿지 않으면 시선 방향에 가장 가까운 축. */
    private static Direction.Axis faceAxis(ServerPlayer player, BlockPos center) {
        HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(center)) {
            return bhr.getDirection().getAxis();
        }
        return Direction.getNearest(player.getLookAngle()).getAxis();
    }

    /** 제련: 광맥·광역이 추가로 부순 블록에도 적용된다. */
    @SubscribeEvent
    public static void onDrops(BlockDropsEvent event) {
        if (!(event.getBreaker() instanceof Player player)) return;
        if (EffectContext.activeLevel(player, Ability.SMELT, event.getTool()) <= 0) return;
        ServerLevel level = event.getLevel();
        for (ItemEntity entity : event.getDrops()) {
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) continue;
            Optional<RecipeHolder<SmeltingRecipe>> recipe = level.getRecipeManager()
                    .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(stack), level);
            if (recipe.isEmpty()) continue;
            ItemStack result = recipe.get().value().getResultItem(level.registryAccess());
            if (result.isEmpty()) continue;
            entity.setItem(result.copyWithCount(result.getCount() * stack.getCount()));
        }
    }
}
