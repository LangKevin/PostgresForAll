package com.postgresforall.app.ui.query;

import com.postgresforall.core.model.QueryResult.Column;
import com.postgresforall.core.service.CellText;

import java.util.List;

/** Result set → TSV / CSV (RFC 4180) / JSON text. Pure functions, unit-tested. */
public final class ResultExport {
    private ResultExport() { }

    /** Tab-separated with a header row; NULL written as "NULL" (paste-friendly). */
    public static String tsv(List<Column> columns, List<Object[]> rows) {
        var sb = new StringBuilder();
        for (var i = 0; i < columns.size(); i++) sb.append(i > 0 ? "\t" : "").append(columns.get(i).name());
        sb.append('\n');
        for (var row : rows) {
            for (var i = 0; i < columns.size(); i++) {
                var v = i < row.length ? row[i] : null;
                sb.append(i > 0 ? "\t" : "").append(v == null ? "NULL" : CellText.of(v).replace('\t', ' ').replace('\n', ' '));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** RFC 4180 CSV with a header row; NULL is an empty field. */
    public static String csv(List<Column> columns, List<Object[]> rows) {
        var sb = new StringBuilder();
        for (var i = 0; i < columns.size(); i++) sb.append(i > 0 ? "," : "").append(csvField(columns.get(i).name()));
        sb.append("\r\n");
        for (var row : rows) {
            for (var i = 0; i < columns.size(); i++) {
                var v = i < row.length ? row[i] : null;
                sb.append(i > 0 ? "," : "").append(v == null ? "" : csvField(CellText.of(v)));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static String csvField(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** JSON array of objects; numbers and booleans stay unquoted, NULL becomes null. */
    public static String json(List<Column> columns, List<Object[]> rows) {
        var sb = new StringBuilder("[");
        for (var r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            sb.append(r > 0 ? ",\n  {" : "\n  {");
            for (var i = 0; i < columns.size(); i++) {
                var v = i < row.length ? row[i] : null;
                sb.append(i > 0 ? ", " : "").append(jsonString(columns.get(i).name())).append(": ");
                if (v == null) sb.append("null");
                else if (v instanceof Number n && Double.isFinite(n.doubleValue())) sb.append(CellText.of(n));
                else if (v instanceof Boolean b) sb.append(b);
                else sb.append(jsonString(CellText.of(v)));
            }
            sb.append('}');
        }
        sb.append(rows.isEmpty() ? "]" : "\n]");
        return sb.toString();
    }

    static String jsonString(String s) {
        var sb = new StringBuilder("\"");
        for (var ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }
}
