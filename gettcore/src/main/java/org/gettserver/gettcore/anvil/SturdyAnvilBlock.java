package org.gettserver.gettcore.anvil;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 튼튼한 모루: 기능은 바닐라 모루와 같고, 써도 금이 가지 않으며({@link SturdyAnvilMenu} + AnvilRepairEvent), 마을 공용으로
 * 두도록 아래가 비어도 떨어지지 않는다(떨어지면 바닐라 규칙으로 깨질 수 있음). 관리자 지급 전용(제작법 없음).
 * {@code #minecraft:anvil} 태그에 들어 있어야 모루 메뉴가 닫히지 않는다.
 */
public class SturdyAnvilBlock extends AnvilBlock {
    public static final MapCodec<AnvilBlock> CODEC = simpleCodec(SturdyAnvilBlock::new);
    private static final Component TITLE = Component.translatable("container.repair");

    public SturdyAnvilBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<AnvilBlock> codec() {
        return CODEC;
    }

    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inv, player) -> new SturdyAnvilMenu(id, inv, ContainerLevelAccess.create(level, pos)),
                TITLE);
    }

    /** 떨어지지 않는다(낙하 예약 틱을 무시). */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    }
}
