package com.postgresforall.app.ui.query;

import com.postgresforall.core.model.QueryResult.Column;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultExportTest {
    private static final List<Column> COLS = List.of(new Column("id", "int4"), new Column("name", "text"),
            new Column("ok", "bool"), new Column("price", "numeric"));
    private static final List<Object[]> ROWS = List.of(
            new Object[]{1, "plain", true, new BigDecimal("3.50")},
            new Object[]{2, "has, comma \"and quotes\"\nnewline", false, null});

    @Test
    void csv_quotesOnlyWhenNeeded_andNullIsEmpty() {
        assertThat(ResultExport.csv(COLS, ROWS)).isEqualTo(
                "id,name,ok,price\r\n"
                        + "1,plain,true,3.50\r\n"
                        + "2,\"has, comma \"\"and quotes\"\"\nnewline\",false,\r\n");
    }

    @Test
    void tsv_hasHeaders_andFlattensTabsAndNewlines() {
        assertThat(ResultExport.tsv(COLS, ROWS)).isEqualTo(
                "id\tname\tok\tprice\n"
                        + "1\tplain\ttrue\t3.50\n"
                        + "2\thas, comma \"and quotes\" newline\tfalse\tNULL\n");
    }

    @Test
    void json_keepsTypes_andEscapes() {
        assertThat(ResultExport.json(COLS, ROWS)).isEqualTo("""
                [
                  {"id": 1, "name": "plain", "ok": true, "price": 3.50},
                  {"id": 2, "name": "has, comma \\"and quotes\\"\\nnewline", "ok": false, "price": null}
                ]""");
        assertThat(ResultExport.json(COLS, List.of())).isEqualTo("[]");
    }
}
