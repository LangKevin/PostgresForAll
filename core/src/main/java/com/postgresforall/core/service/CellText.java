package com.postgresforall.core.service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.HexFormat;

/** How a result cell is shown and copied. One place, so grid, copy and search agree. */
public final class CellText {
    private CellText() { }

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public static String of(Object value) {
        if (value == null) return "NULL";
        if (value instanceof byte[] b) return "\\x" + HexFormat.of().formatHex(b);
        if (value instanceof BigDecimal d) return d.toPlainString();
        if (value instanceof java.time.LocalDateTime t) return ISO_LOCAL.format(t).replace('T', ' ');
        if (value instanceof java.time.OffsetDateTime t) return t.toString().replace('T', ' ');
        if (value instanceof TemporalAccessor t) return t.toString();
        return value.toString();
    }
}
