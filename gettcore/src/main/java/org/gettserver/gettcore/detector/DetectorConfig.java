package org.gettserver.gettcore.detector;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * detector.json: how far, how long and how often the detector scans, and which held items stand for which blocks.
 * A target maps item ids to the block ids they find ({@code ore} marks ore targets); blocks in the
 * {@code selfBlockTags} are found by their own block item.
 */
public record DetectorConfig(int range, int durationSeconds, int cooldownSeconds, int maxBlocks,
                             List<String> selfBlockTags, List<Target> targets) {
    public static final String FILE = "detector.json";

    public static final int DEFAULT_RANGE = 24;
    public static final int DEFAULT_DURATION_SECONDS = 60;
    public static final int DEFAULT_COOLDOWN_SECONDS = 300;
    public static final int DEFAULT_MAX_BLOCKS = 4096;
    private static final List<String> DEFAULT_SELF_BLOCK_TAGS =
            List.of("minecraft:small_flowers", "minecraft:tall_flowers");

    public record Target(String id, List<String> items, List<String> blocks, boolean ore) {
        public static final Codec<Target> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("id", "").forGetter(Target::id),
                Codec.STRING.listOf().optionalFieldOf("items", List.of()).forGetter(Target::items),
                Codec.STRING.listOf().optionalFieldOf("blocks", List.of()).forGetter(Target::blocks),
                // Written even when true, so a hand-edited file shows the switch.
                Codec.BOOL.optionalFieldOf("ore").forGetter(t -> Optional.of(t.ore()))
        ).apply(i, (id, items, blocks, ore) -> new Target(id, items, blocks, ore.orElse(true))));

        public Target {
            items = List.copyOf(items);
            blocks = List.copyOf(blocks);
        }
    }

    private static Target ore(String id, List<String> items, List<String> blocks) {
        return new Target(id, items.stream().map(s -> "minecraft:" + s).toList(),
                blocks.stream().map(s -> "minecraft:" + s).toList(), true);
    }

    private static final List<Target> DEFAULT_TARGETS = List.of(
            ore("coal", List.of("coal", "coal_ore", "deepslate_coal_ore"), List.of("coal_ore", "deepslate_coal_ore")),
            ore("iron", List.of("raw_iron", "iron_ore", "deepslate_iron_ore"),
                    List.of("iron_ore", "deepslate_iron_ore")),
            ore("copper", List.of("raw_copper", "copper_ore", "deepslate_copper_ore"),
                    List.of("copper_ore", "deepslate_copper_ore")),
            ore("gold", List.of("raw_gold", "gold_ore", "deepslate_gold_ore"),
                    List.of("gold_ore", "deepslate_gold_ore")),
            ore("diamond", List.of("diamond", "diamond_ore", "deepslate_diamond_ore"),
                    List.of("diamond_ore", "deepslate_diamond_ore")),
            ore("emerald", List.of("emerald", "emerald_ore", "deepslate_emerald_ore"),
                    List.of("emerald_ore", "deepslate_emerald_ore")),
            ore("redstone", List.of("redstone", "redstone_ore", "deepslate_redstone_ore"),
                    List.of("redstone_ore", "deepslate_redstone_ore")),
            ore("lapis", List.of("lapis_lazuli", "lapis_ore", "deepslate_lapis_ore"),
                    List.of("lapis_ore", "deepslate_lapis_ore")),
            ore("quartz", List.of("quartz", "nether_quartz_ore"), List.of("nether_quartz_ore")),
            ore("nether_gold", List.of("gold_nugget", "nether_gold_ore"), List.of("nether_gold_ore")),
            ore("ancient_debris", List.of("netherite_scrap", "ancient_debris"), List.of("ancient_debris")));

    public static final DetectorConfig DEFAULT = new DetectorConfig(DEFAULT_RANGE, DEFAULT_DURATION_SECONDS,
            DEFAULT_COOLDOWN_SECONDS, DEFAULT_MAX_BLOCKS, DEFAULT_SELF_BLOCK_TAGS, DEFAULT_TARGETS);

    /** Every field is optional on read (a missing one means the default) but always written. */
    public static final Codec<DetectorConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("range").forGetter(c -> Optional.of(c.range())),
            Codec.INT.optionalFieldOf("durationSeconds").forGetter(c -> Optional.of(c.durationSeconds())),
            Codec.INT.optionalFieldOf("cooldownSeconds").forGetter(c -> Optional.of(c.cooldownSeconds())),
            Codec.INT.optionalFieldOf("maxBlocks").forGetter(c -> Optional.of(c.maxBlocks())),
            Codec.STRING.listOf().optionalFieldOf("selfBlockTags").forGetter(c -> Optional.of(c.selfBlockTags())),
            Target.CODEC.listOf().optionalFieldOf("targets").forGetter(c -> Optional.of(c.targets()))
    ).apply(i, (range, duration, cooldown, maxBlocks, tags, targets) -> new DetectorConfig(
            range.orElse(DEFAULT_RANGE), duration.orElse(DEFAULT_DURATION_SECONDS),
            cooldown.orElse(DEFAULT_COOLDOWN_SECONDS), maxBlocks.orElse(DEFAULT_MAX_BLOCKS),
            tags.orElse(DEFAULT_SELF_BLOCK_TAGS), targets.orElse(DEFAULT_TARGETS))));

    /** Server -> client copy (the client picks the targets to draw). */
    public static final StreamCodec<ByteBuf, DetectorConfig> STREAM_CODEC = ByteBufCodecs.stringUtf8(1 << 22).map(
            DetectorConfig::fromJsonString, DetectorConfig::toJsonString);

    public DetectorConfig {
        selfBlockTags = List.copyOf(selfBlockTags);
        targets = List.copyOf(targets);
    }

    public static String toJsonString(DetectorConfig c) {
        return CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow(IllegalStateException::new).toString();
    }

    public static DetectorConfig fromJsonString(String s) {
        return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(s)).getOrThrow(IllegalStateException::new);
    }

    /** Values outside their allowed range become the default, one warning line each. */
    public DetectorConfig sanitized(List<String> warnings) {
        int r = inRange("range", range, 1, 64, DEFAULT_RANGE, warnings);
        int d = inRange("durationSeconds", durationSeconds, 1, 3600, DEFAULT_DURATION_SECONDS, warnings);
        int c = inRange("cooldownSeconds", cooldownSeconds, 0, 86400, DEFAULT_COOLDOWN_SECONDS, warnings);
        int m = inRange("maxBlocks", maxBlocks, 1, 16384, DEFAULT_MAX_BLOCKS, warnings);
        if (r == range && d == durationSeconds && c == cooldownSeconds && m == maxBlocks) return this;
        return new DetectorConfig(r, d, c, m, selfBlockTags, targets);
    }

    private static int inRange(String name, int value, int min, int max, int fallback, List<String> warnings) {
        if (value >= min && value <= max) return value;
        warnings.add(FILE + ": " + name + " " + value + " must be " + min + ".." + max + " (set to " + fallback + ")");
        return fallback;
    }

    /**
     * Everything that makes the target list unusable, one line each ("targets[n] id: ..."): an empty or repeated
     * id, no items or blocks, an item/block id that does not parse, an item claimed by an earlier target.
     */
    public List<String> errors() {
        List<String> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, String> itemOwner = new HashMap<>();
        for (int n = 0; n < targets.size(); n++) {
            Target t = targets.get(n);
            String p = "targets[" + n + "] " + t.id() + ": ";
            if (t.id().isBlank()) out.add(p + "id must not be empty");
            else if (!ids.add(t.id())) out.add(p + "duplicate id");
            if (t.items().isEmpty()) out.add(p + "items must not be empty");
            if (t.blocks().isEmpty()) out.add(p + "blocks must not be empty");
            for (String item : t.items()) {
                if (ResourceLocation.tryParse(item) == null) {
                    out.add(p + "item '" + item + "' is not a valid id");
                    continue;
                }
                String owner = itemOwner.putIfAbsent(item, t.id());
                if (owner != null && !owner.equals(t.id())) {
                    out.add(p + "item " + item + " is already used by target " + owner);
                }
            }
            for (String block : t.blocks()) {
                if (ResourceLocation.tryParse(block) == null) out.add(p + "block '" + block + "' is not a valid id");
            }
        }
        return out;
    }
}
