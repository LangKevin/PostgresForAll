package com.postgresforall.app.ui.diagram;

import com.postgresforall.core.model.Relationships.RelationshipGraph;
import com.postgresforall.core.model.TableObjects.TableRef;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Places diagram boxes in columns around the focus table: tables it references
 * (parents) to the left, tables referencing it (children) to the right, one
 * column per hop. Within a column, boxes are ordered by the average position of
 * the neighbours already placed (fewer crossing lines), wrapped into extra
 * columns past {@link #MAX_PER_COLUMN}, then stacked and centred vertically.
 * Pure geometry, no Swing.
 */
public final class DiagramLayout {
    public static final int COLUMN_GAP = 140;
    public static final int ROW_GAP = 36;
    public static final int MARGIN = 40;
    /** A hop with more tables than this wraps into extra columns further out, so hubs don't make one endless column. */
    public static final int MAX_PER_COLUMN = 6;

    private DiagramLayout() { }

    /** Column index per table: 0 for the focus, negative for parents, positive for children. */
    static Map<TableRef, Integer> columns(RelationshipGraph g) {
        var col = new LinkedHashMap<TableRef, Integer>();
        col.put(g.focus(), 0);
        var queue = new ArrayDeque<TableRef>();
        queue.add(g.focus());
        while (!queue.isEmpty()) {
            var t = queue.poll();
            int c = col.get(t);
            for (var r : g.relationships()) {
                if (r.selfReference()) continue;
                TableRef other;
                int oc;
                if (r.from().equals(t)) {
                    other = r.to();      // t references other: parent goes left
                    oc = c - 1;
                } else if (r.to().equals(t)) {
                    other = r.from();    // other references t: child goes right
                    oc = c + 1;
                } else {
                    continue;
                }
                if (col.containsKey(other)) continue;
                col.put(other, oc);
                queue.add(other);
            }
        }
        // Tables only reachable through edges dropped by a limit: park them next to the focus.
        for (var t : g.tables()) col.putIfAbsent(t.ref(), 1);
        return col;
    }

    /** Model-space rectangle per table; {@code size} measures a box. */
    public static Map<TableRef, Rectangle> layout(RelationshipGraph g, Function<TableRef, Dimension> size) {
        var col = columns(g);
        var byColumn = new TreeMap<Integer, List<TableRef>>();
        for (var e : col.entrySet()) byColumn.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());

        // Order columns outward from the focus so each one sees its placed neighbours.
        var order = new ArrayList<>(byColumn.keySet());
        order.sort(Comparator.comparingInt(Math::abs));
        var rank = new HashMap<TableRef, Double>();
        for (var c : order) {
            var list = byColumn.get(c);
            list.sort(Comparator.comparingDouble((TableRef t) -> barycenter(g, t, rank)).thenComparing(TableRef::toString));
            for (var i = 0; i < list.size(); i++) rank.put(list.get(i), i - (list.size() - 1) / 2.0);
        }

        // Physical columns, left to right. A crowded hop splits into chunks; the
        // first chunk (best-connected tables) stays nearest the focus.
        var physical = new ArrayList<List<TableRef>>();
        for (var e : byColumn.entrySet()) {
            var list = e.getValue();
            var chunks = new ArrayList<List<TableRef>>();
            for (var i = 0; i < list.size(); i += MAX_PER_COLUMN) {
                chunks.add(list.subList(i, Math.min(list.size(), i + MAX_PER_COLUMN)));
            }
            if (e.getKey() < 0) Collections.reverse(chunks);
            physical.addAll(chunks);
        }

        var widths = new ArrayList<Integer>();
        var heights = new ArrayList<Integer>();
        for (var column : physical) {
            int w = 0, h = 0;
            for (var t : column) {
                var d = size.apply(t);
                w = Math.max(w, d.width);
                h += d.height + ROW_GAP;
            }
            widths.add(w);
            heights.add(Math.max(0, h - ROW_GAP));
        }
        var tallest = heights.stream().mapToInt(Integer::intValue).max().orElse(0);

        var out = new LinkedHashMap<TableRef, Rectangle>();
        var x = MARGIN;
        for (var i = 0; i < physical.size(); i++) {
            int colWidth = widths.get(i);
            var y = MARGIN + (tallest - heights.get(i)) / 2;
            for (var t : physical.get(i)) {
                var d = size.apply(t);
                out.put(t, new Rectangle(x + (colWidth - d.width) / 2, y, d.width, d.height));
                y += d.height + ROW_GAP;
            }
            x += colWidth + COLUMN_GAP;
        }
        return out;
    }

    private static double barycenter(RelationshipGraph g, TableRef t, Map<TableRef, Double> rank) {
        double sum = 0;
        int n = 0;
        for (var r : g.relationships()) {
            TableRef other = r.from().equals(t) ? r.to() : r.to().equals(t) ? r.from() : null;
            if (other == null || other.equals(t)) continue;
            var k = rank.get(other);
            if (k != null) {
                sum += k;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Smallest rectangle holding every box, plus the margin. */
    public static Rectangle bounds(Map<TableRef, Rectangle> boxes) {
        Rectangle all = null;
        for (var r : boxes.values()) all = all == null ? new Rectangle(r) : all.union(r);
        if (all == null) return new Rectangle(0, 0, 0, 0);
        return new Rectangle(0, 0, all.x + all.width + MARGIN, all.y + all.height + MARGIN);
    }
}
