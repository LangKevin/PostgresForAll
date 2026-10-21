package com.postgresforall.app.ui.diagram;

import com.postgresforall.core.model.Relationships.DiagramTable;
import com.postgresforall.core.model.Relationships.Relationship;
import com.postgresforall.core.model.Relationships.RelationshipGraph;
import com.postgresforall.core.model.TableObjects.TableRef;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class DiagramLayoutTest {

    private static TableRef t(String n) {
        return new TableRef("public", n);
    }

    private static DiagramTable table(String n, int distance) {
        return new DiagramTable(t(n), List.of(), -1, null, distance);
    }

    private static Relationship fk(String from, String to) {
        return new Relationship(from + "_" + to + "_fkey", t(from), List.of(to + "_id"), t(to), List.of("id"),
                "NO ACTION", "NO ACTION", true, false, false);
    }

    /** orders → customers, order_items → orders, order_items → products, employees → employees. */
    private static RelationshipGraph shop() {
        return new RelationshipGraph(t("orders"),
                List.of(table("orders", 0), table("customers", 1), table("order_items", 1), table("products", 2)),
                List.of(fk("orders", "customers"), fk("order_items", "orders"), fk("order_items", "products")),
                false);
    }

    @Test
    void parentsGoLeft_childrenGoRight() {
        var cols = DiagramLayout.columns(shop());
        assertThat(cols).containsEntry(t("orders"), 0)
                .containsEntry(t("customers"), -1)
                .containsEntry(t("order_items"), 1)
                .containsEntry(t("products"), 0);
    }

    @Test
    void boxesDoNotOverlap_andColumnsAreOrderedLeftToRight() {
        Map<TableRef, Rectangle> boxes = DiagramLayout.layout(shop(), r -> new Dimension(180, 120));
        assertThat(boxes).hasSize(4);
        var all = List.copyOf(boxes.values());
        for (var i = 0; i < all.size(); i++) {
            for (var j = i + 1; j < all.size(); j++) assertThat(all.get(i).intersects(all.get(j))).isFalse();
        }
        assertThat(boxes.get(t("customers")).x).isLessThan(boxes.get(t("orders")).x);
        assertThat(boxes.get(t("orders")).x).isLessThan(boxes.get(t("order_items")).x);
        assertThat(boxes.get(t("orders")).x).isEqualTo(boxes.get(t("products")).x);
        var b = DiagramLayout.bounds(boxes);
        for (var r : all) assertThat(b.contains(r)).isTrue();
    }

    @Test
    void selfReferenceAndLoneTable_stayInTheCentreColumn() {
        var g = new RelationshipGraph(t("employees"), List.of(table("employees", 0)),
                List.of(fk("employees", "employees")), false);
        var boxes = DiagramLayout.layout(g, r -> new Dimension(200, 80));
        assertThat(boxes.get(t("employees"))).isEqualTo(new Rectangle(DiagramLayout.MARGIN, DiagramLayout.MARGIN, 200, 80));
    }

    @Test
    void columnsOfDifferentHeightsAreCentredVertically() {
        var g = new RelationshipGraph(t("a"), List.of(table("a", 0), table("b", 1), table("c", 1)),
                List.of(fk("b", "a"), fk("c", "a")), false);
        var boxes = DiagramLayout.layout(g, r -> new Dimension(100, 100));
        var a = boxes.get(t("a"));
        var b = boxes.get(t("b"));
        var c = boxes.get(t("c"));
        assertThat(a.getCenterY()).isEqualTo((b.getCenterY() + c.getCenterY()) / 2);
    }

    @Test
    void crowdedHopWrapsIntoExtraColumnsOnItsOwnSide() {
        var tables = new ArrayList<DiagramTable>(List.of(table("hub", 0), table("parent", 1)));
        var rels = new ArrayList<Relationship>(List.of(fk("hub", "parent")));
        for (var i = 0; i < 14; i++) {
            tables.add(table("child" + i, 1));
            rels.add(fk("child" + i, "hub"));
        }
        var boxes = DiagramLayout.layout(new RelationshipGraph(t("hub"), tables, rels, false), r -> new Dimension(150, 90));
        var hub = boxes.get(t("hub"));
        var xs = new TreeSet<Integer>();
        for (var i = 0; i < 14; i++) {
            var r = boxes.get(t("child" + i));
            assertThat(r.x).isGreaterThan(hub.x);
            xs.add(r.x);
        }
        assertThat(xs).hasSize(3); // 14 children, 6 per column
        assertThat(boxes.get(t("parent")).x).isLessThan(hub.x);
        var all = List.copyOf(boxes.values());
        for (var i = 0; i < all.size(); i++) {
            for (var j = i + 1; j < all.size(); j++) assertThat(all.get(i).intersects(all.get(j))).isFalse();
        }
    }
}
