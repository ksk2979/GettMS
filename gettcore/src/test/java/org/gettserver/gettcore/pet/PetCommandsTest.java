package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PetCommandsTest {
    private static final PetConfig.Capture CAP = PetConfig.DEFAULT.capture();
    private static final PetSpecies LION = new PetSpecies("naturalist:lion", PetSpecies.Kind.FIGHTER,
            ResourceLocation.fromNamespaceAndPath("gettcore", "pet_lion"), 2, 0.3, 1, 0.6, 0.3, false, 1, 1, 1, 0, 1);

    private static TranslatableContents tc(Component c) {
        return (TranslatableContents) c.getContents();
    }

    @Test
    void capturableShowsChancePerTierAtCurrentHealth() {
        List<Component> lines = PetCommands.mobInfoLines(Component.literal("Lion"), 2.1f, 20f, Optional.of(LION),
                false, CAP);
        assertEquals(6, lines.size());
        assertEquals("gettcore.msg.mobinfo.header", tc(lines.get(0)).getKey());
        assertEquals("2.1", tc(lines.get(0)).getArgs()[1]);
        assertEquals("20", tc(lines.get(0)).getArgs()[2]);
        assertEquals(11, tc(lines.get(0)).getArgs()[3]);
        assertEquals("gettcore.msg.mobinfo.capturable", tc(lines.get(1)).getKey());
        assertArrayEquals(new Object[]{"20"}, tc(lines.get(1)).getArgs());
        TranslatableContents iron = tc(lines.get(2));
        assertEquals("gettcore.msg.mobinfo.chance_line", iron.getKey());
        assertEquals("gettcore.tier.iron", tc((Component) iron.getArgs()[0]).getKey());
        assertEquals("15", iron.getArgs()[1]);   // hp 10.5% -> midpoint of 5..25
        assertEquals("gettcore.tier.netherite", tc((Component) tc(lines.get(5)).getArgs()[0]).getKey());
    }

    @Test
    void notSpeciesAndOwnedHaveReason() {
        var none = PetCommands.mobInfoLines(Component.literal("Pig"), 10, 10, Optional.empty(), false, CAP);
        assertEquals(2, none.size());
        assertEquals("gettcore.msg.mobinfo.not_species", tc(none.get(1)).getKey());
        var owned = PetCommands.mobInfoLines(Component.literal("Lion"), 1, 10, Optional.of(LION), true, CAP);
        assertEquals(2, owned.size());
        assertEquals("gettcore.msg.mobinfo.owned", tc(owned.get(1)).getKey());
    }

    @Test
    void fmtDropsTrailingZero() {
        assertEquals("20", PetCommands.fmt(20.0));
        assertEquals("15.5", PetCommands.fmt(15.526));
        assertEquals("0", PetCommands.fmt(0));
    }

    @Test
    void byNumberIsOneBased() {
        PetKey a = new PetKey("naturalist:lion", "");
        PetKey b = new PetKey("naturalist:bear", "naturalist:brown_bear");
        List<PetKey> owned = List.of(a, b);
        assertEquals(Optional.of(a), PetCommands.byNumber(owned, 1));
        assertEquals(Optional.of(b), PetCommands.byNumber(owned, 2));
        assertEquals(Optional.empty(), PetCommands.byNumber(owned, 0));
        assertEquals(Optional.empty(), PetCommands.byNumber(owned, 3));
        assertEquals(Optional.empty(), PetCommands.byNumber(List.of(), 1));
    }
}
