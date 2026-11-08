package org.gettserver.gettcore.pet.client;

/** Pure paging maths for the pet dex screen (no Minecraft imports, unit-testable). */
public final class PetDexPaging {
    private PetDexPaging() {}

    public static int pageCount(int owned, int perPage) {
        return Math.max(1, (owned + perPage - 1) / perPage);
    }

    /** Entries shown on {@code page} (0-based) when {@code owned} pets are split {@code perPage} per page. */
    public static int countOnPage(int owned, int page, int perPage) {
        return Math.max(0, Math.min(perPage, owned - page * perPage));
    }

    /** Grid rows needed for {@code countOnPage} cells: at least 1 (the empty message), at most {@code maxRows}. */
    public static int rowsFor(int countOnPage, int cols, int maxRows) {
        int rows = (countOnPage + cols - 1) / cols;
        return Math.max(1, Math.min(maxRows, rows));
    }

    /** How many grid rows of {@code cell} px with {@code gap} px between fit into {@code available} px (1..maxRows). */
    public static int rowsThatFit(int available, int cell, int gap, int maxRows) {
        int rows = (available + gap) / (cell + gap);
        return Math.max(1, Math.min(maxRows, rows));
    }
}
