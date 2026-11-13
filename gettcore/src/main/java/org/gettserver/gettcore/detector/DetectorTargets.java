package org.gettserver.gettcore.detector;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/** Which blocks a held item (or a mined ore) stands for, resolved from {@link DetectorConfig}. */
public final class DetectorTargets {
    private DetectorTargets() {}

    public record Resolved(String key, Set<String> blockIds, boolean ore) {
        public Resolved {
            blockIds = Set.copyOf(blockIds);
        }
    }

    /**
     * The target listing {@code itemId} among its items. Otherwise, when the block the item places
     * ({@code itemBlockId}, null if none) satisfies {@code selfTagged}, that block stands for itself (not an ore).
     */
    public static Optional<Resolved> forItem(DetectorConfig c, String itemId, @Nullable String itemBlockId,
                                             Predicate<String> selfTagged) {
        for (DetectorConfig.Target t : c.targets()) {
            if (t.items().contains(itemId)) {
                return Optional.of(new Resolved(t.id(), new HashSet<>(t.blocks()), t.ore()));
            }
        }
        if (itemBlockId != null && selfTagged.test(itemBlockId)) {
            return Optional.of(new Resolved(itemBlockId, Set.of(itemBlockId), false));
        }
        return Optional.empty();
    }

    /** For the enchant: the ore target listing {@code blockId}, else any block with an ore tag stands for itself. */
    public static Optional<Resolved> forOreBlock(DetectorConfig c, String blockId, boolean isOreTag) {
        for (DetectorConfig.Target t : c.targets()) {
            if (t.ore() && t.blocks().contains(blockId)) {
                return Optional.of(new Resolved(t.id(), new HashSet<>(t.blocks()), true));
            }
        }
        if (isOreTag) return Optional.of(new Resolved(blockId, Set.of(blockId), true));
        return Optional.empty();
    }

    public static Optional<Resolved> forStack(DetectorConfig c, ItemStack stack) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Block block = stack.getItem() instanceof BlockItem bi ? bi.getBlock() : null;
        String blockId = block == null ? null : BuiltInRegistries.BLOCK.getKey(block).toString();
        return forItem(c, itemId, blockId, id -> block != null && selfTagged(c, block));
    }

    public static Optional<Resolved> forOreState(DetectorConfig c, BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return forOreBlock(c, id, state.is(Tags.Blocks.ORES));
    }

    /** The registered blocks of a target; ids that are not registered are skipped. */
    public static Set<Block> blocks(Resolved r) {
        Set<Block> out = new LinkedHashSet<>();
        for (String id : r.blockIds()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(out::add);
        }
        return out;
    }

    private static boolean selfTagged(DetectorConfig c, Block block) {
        for (String tag : c.selfBlockTags()) {
            ResourceLocation rl = ResourceLocation.tryParse(tag);
            if (rl == null) continue;
            if (block.builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, rl))) return true;
        }
        return false;
    }
}
