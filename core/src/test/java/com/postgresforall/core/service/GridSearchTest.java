package com.postgresforall.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GridSearchTest {
    private static final int ID = 0;
    private static final int EMAIL = 1;
    private static final int PRIORITY = 2;

    private static List<Object[]> makeTable() {
        return List.of(
                new Object[]{1, "alice@example.com", "LOW"},
                new Object[]{2, "bob@example.com", "HIGH"},
                new Object[]{3, "carol@example.com", "LOW"},
                new Object[]{4, "dave@example.com", "MEDIUM"},
                new Object[]{5, null, null},
                new Object[]{6, "evan@example.com", "HIGH"});
    }

    @Test
    void findFirst_locatesFirstMatchingRow() {
        var t = makeTable();
        assertThat(GridSearch.findFirst(t, EMAIL, "alice", false)).isEqualTo(0);
        assertThat(GridSearch.findFirst(t, PRIORITY, "HIGH", false)).isEqualTo(1);
    }

    @Test
    void findNext_movesPastCurrentCursor() {
        assertThat(GridSearch.findNext(makeTable(), PRIORITY, "HIGH", 1, false, false)).isEqualTo(5);
    }

    @Test
    void findNext_wrapsAroundWhenEnabled() {
        assertThat(GridSearch.findNext(makeTable(), PRIORITY, "HIGH", 5, true, false)).isEqualTo(1);
    }

    @Test
    void findNext_returnsNegativeWhenNoMatchAndNoWrap() {
        assertThat(GridSearch.findNext(makeTable(), PRIORITY, "HIGH", 5, false, false)).isEqualTo(-1);
    }

    @Test
    void search_isCaseInsensitive() {
        var t = makeTable();
        assertThat(GridSearch.findFirst(t, EMAIL, "ALICE", false)).isEqualTo(0);
        assertThat(GridSearch.findFirst(t, PRIORITY, "high", false)).isEqualTo(1);
    }

    @Test
    void search_skipsNulls_byDefault() {
        assertThat(GridSearch.findFirst(makeTable(), EMAIL, "null", false)).isEqualTo(-1);
    }

    @Test
    void search_matchesNulls_whenOptedIn() {
        var t = makeTable();
        assertThat(GridSearch.findFirst(t, EMAIL, "NULL", true)).isEqualTo(4);
        assertThat(GridSearch.findFirst(t, EMAIL, "null", true)).isEqualTo(4);
    }

    @Test
    void search_emptyNeedle_returnsNegative() {
        var t = makeTable();
        assertThat(GridSearch.findFirst(t, EMAIL, "", false)).isEqualTo(-1);
        assertThat(GridSearch.findFirst(t, EMAIL, "   ", false)).isEqualTo(-1);
    }

    @Test
    void search_unknownColumn_returnsNegative() {
        assertThat(GridSearch.findFirst(makeTable(), -1, "alice", false)).isEqualTo(-1);
        assertThat(GridSearch.findFirst(makeTable(), 9, "alice", false)).isEqualTo(-1);
    }

    @Test
    void search_matchesSubstring_notJustExact() {
        assertThat(GridSearch.findFirst(makeTable(), EMAIL, "@example", false)).isEqualTo(0);
    }

    @Test
    void search_matchesAcrossTypeBoundaries() {
        assertThat(GridSearch.findFirst(makeTable(), ID, "5", false)).isEqualTo(4);
    }
}
