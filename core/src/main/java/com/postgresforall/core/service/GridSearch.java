package com.postgresforall.core.service;

import java.util.List;
import java.util.Locale;

/**
 * Pure search helper used by the column-find dialog. Case-insensitive
 * substring match against each cell's text. Skips nulls unless
 * {@code matchNullsAsText} is true (then they match against "NULL").
 */
public final class GridSearch {
    private GridSearch() { }

    /**
     * Finds the next matching row in {@code rows}, starting strictly after
     * {@code startRowExclusive}; wraps to 0 when {@code wrap}. Returns -1 if none.
     */
    public static int findNext(List<Object[]> rows, int column, String needle, int startRowExclusive,
                               boolean wrap, boolean matchNullsAsText) {
        if (rows == null || needle == null || needle.isEmpty() || column < 0) return -1;
        var n = needle.trim();
        if (n.isEmpty()) return -1;

        for (var i = startRowExclusive + 1; i < rows.size(); i++) {
            if (matches(cell(rows.get(i), column), n, matchNullsAsText)) return i;
        }
        if (wrap) {
            for (var i = 0; i <= startRowExclusive && i < rows.size(); i++) {
                if (matches(cell(rows.get(i), column), n, matchNullsAsText)) return i;
            }
        }
        return -1;
    }

    public static int findFirst(List<Object[]> rows, int column, String needle, boolean matchNullsAsText) {
        return findNext(rows, column, needle, -1, false, matchNullsAsText);
    }

    private static Object cell(Object[] row, int column) {
        return column < row.length ? row[column] : null;
    }

    private static boolean matches(Object cell, String needle, boolean matchNullsAsText) {
        var lower = needle.toLowerCase(Locale.ROOT);
        if (cell == null) return matchNullsAsText && "null".contains(lower);
        return CellText.of(cell).toLowerCase(Locale.ROOT).contains(lower);
    }
}
