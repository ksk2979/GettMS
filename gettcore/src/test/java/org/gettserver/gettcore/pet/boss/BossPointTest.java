package org.gettserver.gettcore.pet.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class BossPointTest {
    @Test
    void codecRoundTripKeepsDimensionAndPos() {
        BossPoint p = new BossPoint("lion_hill", Level.NETHER, new BlockPos(-12, 70, 345), 24,
                ResourceLocation.parse("gettcore:boss_lion"), 2, 15);
        JsonElement json = BossPoint.CODEC.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        BossPoint back = BossPoint.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(p, back);
        assertEquals("minecraft:the_nether", json.getAsJsonObject().get("dimension").getAsString());
    }

    @Test
    void listCodecRoundTrip() {
        List<BossPoint> list = List.of(
                new BossPoint("a", Level.OVERWORLD, BlockPos.ZERO, 16, ResourceLocation.parse("gettcore:boss_bear"), 1, 30),
                new BossPoint("b", ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("custom:dim")),
                        new BlockPos(1, 2, 3), 8, ResourceLocation.parse("gettcore:boss_vulture"), 0, 5));
        JsonElement json = BossPoint.CODEC.listOf().encodeStart(JsonOps.INSTANCE, list).getOrThrow();
        assertEquals(list, BossPoint.CODEC.listOf().parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void optionalFieldsDefault() {
        JsonElement json = JsonParser.parseString(
                "{\"id\":\"x\",\"dimension\":\"minecraft:overworld\",\"pos\":[1,64,2],\"bossType\":\"gettcore:boss_tiger\"}");
        BossPoint p = BossPoint.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(new BossPoint("x", Level.OVERWORLD, new BlockPos(1, 64, 2), 16,
                ResourceLocation.parse("gettcore:boss_tiger"), 1, 30), p);
    }
}
