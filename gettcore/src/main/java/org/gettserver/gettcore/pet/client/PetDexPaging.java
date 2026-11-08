package org.gettserver.gettcore.pet.client;

/** Pure paging maths for the pet dex screen (no Minecraft imports, unit-testable). */
public final class PetDexPaging {
    private PetDexPaging() {}

    public static int pageCount(int owned, int perPage) {
        return Math.max(1, (owned + perPage - 1) / perPage);
    }
}
