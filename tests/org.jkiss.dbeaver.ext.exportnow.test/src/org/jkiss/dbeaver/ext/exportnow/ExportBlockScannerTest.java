/*
 * DBeaver headless export command — tests
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.ext.exportnow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the document scanner behind the execution semantics:
 * no selection → all blocks; selection → blocks whose command is inside it.
 */
public class ExportBlockScannerTest {

    private static final String SCRIPT = """
        @exportnow {"type": "csv", "consumer": {"outputFilePattern": "customers"}} select * from customers;
        @exportnow {"type": "csv", "consumer": {"outputFilePattern": "orders"}};


        select * from orders;
        select 'no export here';
        """;

    @Test
    public void findsInlineAndClassicBlocks() {
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(SCRIPT);
        assertEquals(2, blocks.size());
        assertEquals("select * from customers", blocks.get(0).sql());
        assertEquals("select * from orders", blocks.get(1).sql());
    }

    @Test
    public void classicBlockSkipsBlankLinesAndComments() {
        String text = """
            @exportnow {"type": "csv"};

            -- a comment

            select *
            from t1;
            """;
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(text);
        assertEquals(1, blocks.size());
        assertEquals("select *\nfrom t1", blocks.get(0).sql());
    }

    @Test
    public void multilineCommandBlock() {
        String text = """
            @@exportnow {"type": "csv"}
            select o.*
            from orders o
            @@
            select 'after';
            """;
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(text);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).sql().contains("from orders o"));
    }

    @Test
    public void quotedSemicolonsAndBracesDoNotSplit() {
        String text = """
            @exportnow {"type": "csv", "consumer": {"outputFilePattern": "we};rd"}};
            select ';' as x, 'don''t' as y;
            """;
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(text);
        assertEquals(1, blocks.size());
        assertEquals("select ';' as x, 'don''t' as y", blocks.get(0).sql());
    }

    @Test
    public void selectionRangeFiltersBlocks() {
        int secondCmd = SCRIPT.indexOf("@exportnow", 1 + SCRIPT.indexOf("@exportnow"));
        // selection covering only the second (classic) block
        List<ExportBlockScanner.Block> blocks =
            ExportBlockScanner.findBlocks(SCRIPT, secondCmd, SCRIPT.length() - secondCmd);
        assertEquals(1, blocks.size());
        assertEquals("select * from orders", blocks.get(0).sql());
        // selection covering only the first line
        blocks = ExportBlockScanner.findBlocks(SCRIPT, 0, SCRIPT.indexOf('\n'));
        assertEquals(1, blocks.size());
        assertEquals("select * from customers", blocks.get(0).sql());
    }

    @Test
    public void textWithoutBlocksYieldsNothing() {
        assertTrue(ExportBlockScanner.findBlocks("select 1;\nselect 2;\n").isEmpty());
        // command directly followed by another command has no query to export
        assertTrue(ExportBlockScanner.findBlocks(
            "@exportnow {\"type\": \"csv\"};\n@exportnow {\"type\": \"csv\"};\n").isEmpty());
    }

    @Test
    public void malformedBlockIsSkipped() {
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks("""
            @exportnow {broken json;
            select * from t1;
            @exportnow {"type": "csv"};
            select * from t2;
            """);
        assertEquals(1, blocks.size());
        assertEquals("select * from t2", blocks.get(0).sql());
    }
}
