package org.gettserver.gettcore.craft;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 제작대: a bench two blocks wide and two tall (a table with a tool board behind it). Using any of its four parts
 * opens the crafting screen. The part the player placed ({@link Part#MAIN}) carries the model; the other three are
 * invisible parts of the same block that only take up the space. Breaking any part removes the whole bench and drops
 * one item.
 *
 * <p>FACING is the side the front looks at (towards whoever placed it). Seen from the front, MAIN is the lower
 * right block: the bench extends one block to FACING's counter-clockwise side and one block up.
 */
public class CraftBenchBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<CraftBenchBlock> CODEC = simpleCodec(CraftBenchBlock::new);
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public enum Part implements StringRepresentable {
        MAIN("main", 0, 0), SIDE("side", 1, 0), TOP("top", 0, 1), TOP_SIDE("top_side", 1, 1);

        private final String name;
        final int side;
        final int up;

        Part(String name, int side, int up) {
            this.name = name;
            this.side = side;
            this.up = up;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public CraftBenchBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.MAIN));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    private static Direction sideOf(Direction facing) {
        return facing.getCounterClockWise();
    }

    public static BlockPos partPos(BlockPos main, Direction facing, Part part) {
        return main.relative(sideOf(facing), part.side).above(part.up);
    }

    public static BlockPos mainPos(BlockPos pos, BlockState state) {
        Part part = state.getValue(PART);
        return pos.relative(sideOf(state.getValue(FACING)), -part.side).below(part.up);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos main = context.getClickedPos();
        for (Part part : Part.values()) {
            if (part == Part.MAIN) continue;
            BlockPos p = partPos(main, facing, part);
            if (p.getY() >= level.getMaxBuildHeight() || !level.getWorldBorder().isWithinBounds(p)
                    || !level.getBlockState(p).canBeReplaced(context)) {
                return null;
            }
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, Part.MAIN);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        Direction facing = state.getValue(FACING);
        for (Part part : Part.values()) {
            if (part != Part.MAIN) level.setBlock(partPos(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // The rest of the bench goes with it, without drops: the part that was broken drops the one item.
            BlockPos main = mainPos(pos, state);
            Direction facing = state.getValue(FACING);
            for (Part part : Part.values()) {
                BlockPos p = partPos(main, facing, part);
                if (p.equals(pos)) continue;
                BlockState other = level.getBlockState(p);
                if (other.is(this) && other.getValue(FACING) == facing && other.getValue(PART) == part) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) CraftService.open(sp, Vec3.atCenterOf(pos));
        return InteractionResult.CONSUME;
    }

    private static final VoxelShape BOARD_NORTH = Block.box(0, 0, 13, 16, 16, 16);
    private static final VoxelShape BOARD_SOUTH = Block.box(0, 0, 0, 16, 16, 3);
    private static final VoxelShape BOARD_WEST = Block.box(13, 0, 0, 16, 16, 16);
    private static final VoxelShape BOARD_EAST = Block.box(0, 0, 0, 3, 16, 16);

    /** The table fills its two blocks; the tool board is a thin panel at the back of the two upper ones. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(PART).up == 0) return Shapes.block();
        return switch (state.getValue(FACING)) {
            case SOUTH -> BOARD_SOUTH;
            case WEST -> BOARD_WEST;
            case EAST -> BOARD_EAST;
            default -> BOARD_NORTH;
        };
    }
}
