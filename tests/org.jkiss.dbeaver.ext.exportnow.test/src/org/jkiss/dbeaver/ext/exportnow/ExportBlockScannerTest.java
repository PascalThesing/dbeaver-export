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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jkiss.dbeaver.model.sql.registry.SQLCommandsRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the document scanner behind the execution semantics:
 * no selection → all blocks; selection → blocks whose command is inside it.
 * The scanner delegates statement splitting to DBeaver's SQLScriptParser,
 * which requires the {@code exportnow} command to be registered.
 */
public class ExportBlockScannerTest {

    private static final String SCRIPT = """
        @exportnow {"type": "csv", "consumer": {"outputFilePattern": "customers"}} select * from customers;
        @exportnow {"type": "csv", "consumer": {"outputFilePattern": "orders"}};


        select * from orders;
        select 'no export here';
        """;

    @BeforeAll
    public static void commandMustBeRegistered() {
        // Command recognition inside the SQL parser depends on the extension registry
        assertNotNull(SQLCommandsRegistry.getInstance().getCommandHandler("exportnow"),
            "exportnow command must be registered before parser rules are built");
    }

    @Test
    public void findsInlineAndClassicBlocks() {
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(null, SCRIPT);
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
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(null, text);
        assertEquals(1, blocks.size());
        assertEquals("select *\nfrom t1", blocks.get(0).sql());
    }

    @Test
    public void classicBlockSkipsBlockComments() {
        String text = """
            @exportnow {"type": "csv"};
            /* a block comment;
               with a semicolon inside */
            select * from t1;
            """;
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(null, text);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).sql().contains("from t1"),
            "semicolon inside a block comment must not truncate the statement");
    }

    @Test
    public void trailingLineCommentWithSemicolonDoesNotSplit() {
        String text = """
            @exportnow {"type": "csv"};
            select * -- not the end;
            from t1;
            """;
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(null, text);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).sql().contains("from t1"),
            "semicolon inside a trailing line comment must not truncate the statement");
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
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(null, text);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).sql().contains("from orders o"));
    }

    @Test
    public void unterminatedMultilineCommandDegradesToSingleLineForm() {
        String text = """
            @@exportnow {"type": "csv"}
            select * from t1;
            """;
        // no closing @@ - the parser falls back to matching the single-@ form,
        // so the block still exports the following statement
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(null, text);
        assertEquals(1, blocks.size());
        assertEquals("select * from t1", blocks.get(0).sql());
    }

    @Test
    public void quotedSemicolonsAndBracesDoNotSplit() {
        String text = """
            @exportnow {"type": "csv", "consumer": {"outputFilePattern": "we};rd"}};
            select ';' as x, 'don''t' as y;
            """;
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(null, text);
        assertEquals(1, blocks.size());
        assertEquals("select ';' as x, 'don''t' as y", blocks.get(0).sql());
    }

    @Test
    public void crlfLineEndings() {
        String text = "@exportnow {\"type\": \"csv\"};\r\n\r\nselect * from t1;\r\n";
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(null, text);
        assertEquals(1, blocks.size());
        assertEquals("select * from t1", blocks.get(0).sql());
    }

    @Test
    public void selectionRangeFiltersBlocks() {
        int secondCmd = SCRIPT.indexOf("@exportnow", 1 + SCRIPT.indexOf("@exportnow"));
        // selection covering only the second (classic) block
        List<ExportBlockScanner.Block> blocks =
            ExportBlockScanner.findBlocks(null, SCRIPT, secondCmd, SCRIPT.length() - secondCmd);
        assertEquals(1, blocks.size());
        assertEquals("select * from orders", blocks.get(0).sql());
        // selection covering only the first line
        blocks = ExportBlockScanner.findBlocks(null, SCRIPT, 0, SCRIPT.indexOf('\n'));
        assertEquals(1, blocks.size());
        assertEquals("select * from customers", blocks.get(0).sql());
    }

    @Test
    public void textWithoutBlocksYieldsNothing() {
        assertTrue(ExportBlockScanner.findBlocks(null, "select 1;\nselect 2;\n").isEmpty());
        // command directly followed by another command has no query to export
        assertTrue(ExportBlockScanner.findBlocks(null,
            "@exportnow {\"type\": \"csv\"};\n@exportnow {\"type\": \"csv\"};\n").isEmpty());
    }

    @Test
    public void malformedBlockIsSkipped() {
        List<ExportBlockScanner.Block> blocks = ExportBlockScanner.findBlocks(null, """
            @exportnow {broken json;
            select * from t1;
            @exportnow {"type": "csv"};
            select * from t2;
            """);
        assertEquals(1, blocks.size());
        assertEquals("select * from t2", blocks.get(0).sql());
    }
}
