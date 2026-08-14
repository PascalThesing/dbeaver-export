/*
 * DBeaver headless export command
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.ext.exportnow;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocumentPartitioner;
import org.eclipse.jface.text.rules.FastPartitioner;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.sql.SQLControlCommand;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.SQLSyntaxManager;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.sql.parser.SQLParserContext;
import org.jkiss.dbeaver.model.sql.parser.SQLParserPartitions;
import org.jkiss.dbeaver.model.sql.SQLPartitionScanner;
import org.jkiss.dbeaver.model.sql.parser.SQLRuleManager;
import org.jkiss.dbeaver.model.sql.parser.SQLScriptParser;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds {@code @exportnow} blocks in a SQL script using DBeaver's own script
 * parser, which understands dialect string escapes, line and block comments,
 * custom delimiters, {@code BEGIN/END} blocks — and both command forms:
 * single-line {@code @exportnow ...} and multiline {@code @@exportnow ... @@}.
 * The query of a "classic" block (no inline SQL) is the next parsed statement.
 */
final class ExportBlockScanner {

    private static final Log log = Log.getLog(ExportBlockScanner.class);

    /** One export block: command offset, parsed settings (+ optional inline SQL) and resolved SQL. */
    record Block(int offset, @NotNull ExportCommandParser.ParsedCommand command, @NotNull String sql) {
    }

    private final ExportCommandParser commandParser = new ExportCommandParser();

    /** Scans the whole text. */
    @NotNull
    List<Block> findBlocks(@Nullable DBPDataSource dataSource, @NotNull String text) {
        return findBlocks(dataSource, text, 0, text.length());
    }

    /**
     * Scans only blocks whose command starts inside {@code [offset, offset+length)}.
     * The block's SQL may extend beyond the range (DBeaver discards selected text
     * after a control command anyway, so the command position is what counts).
     */
    @NotNull
    List<Block> findBlocks(@Nullable DBPDataSource dataSource, @NotNull String text, int offset, int length) {
        final List<Block> blocks = new ArrayList<>();
        final int rangeEnd = offset + length;

        // Parse the whole document: a range-bounded parse would cut off the query
        // following a command near the end of a selection.
        final List<SQLScriptElement> elements;
        final SQLDialect dialect = dataSource == null ? BasicSQLDialect.INSTANCE : dataSource.getSQLDialect();
        try {
            final SQLParserContext parserContext = createParserContext(dataSource, dialect, text);
            elements = SQLScriptParser.extractScriptQueries(parserContext, 0, text.length(), true, false, false);
        } catch (Throwable e) {
            log.warn("@exportnow: can't parse script", e);
            return blocks;
        }

        for (int i = 0; i < elements.size(); i++) {
            if (!(elements.get(i) instanceof SQLControlCommand command)
                || !SQLCommandExportNow.COMMAND_ID.equals(command.getCommandId())) {
                continue;
            }
            if (command.getOffset() < offset || command.getOffset() >= rangeEnd) {
                continue;
            }
            final ExportCommandParser.ParsedCommand parsed;
            try {
                parsed = commandParser.parseParameters(command.getParameter());
            } catch (Exception e) {
                log.warn("@exportnow: skipping malformed block at offset " + command.getOffset()
                    + ": " + e.getMessage());
                continue;
            }
            String sql = parsed.sql();
            if (sql == null
                && i + 1 < elements.size()
                && elements.get(i + 1) instanceof SQLQuery nextQuery) {
                // the parsed element may keep leading comment lines - not needed for the export
                sql = SQLUtils.stripComments(dialect, nextQuery.getText()).trim();
            }
            if (sql != null && !sql.isBlank()) {
                blocks.add(new Block(command.getOffset(), parsed, sql));
            }
        }
        return blocks;
    }

    @NotNull
    private SQLParserContext createParserContext(
        @Nullable DBPDataSource dataSource,
        @NotNull SQLDialect dialect,
        @NotNull String text
    ) {
        DBPPreferenceStore preferences = dataSource == null
            ? null : dataSource.getContainer().getPreferenceStore();
        if (preferences == null) {
            preferences = DBWorkbench.getPlatform().getPreferenceStore();
        }
        final SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dialect, preferences);
        final SQLRuleManager ruleManager = new SQLRuleManager(syntaxManager);
        ruleManager.loadRules(dataSource, false);
        final Document document = new Document(text);
        final IDocumentPartitioner partitioner = new FastPartitioner(
            new SQLPartitionScanner(dataSource, dialect, ruleManager),
            SQLParserPartitions.SQL_CONTENT_TYPES);
        partitioner.connect(document);
        document.setDocumentPartitioner(SQLParserPartitions.SQL_PARTITIONING, partitioner);
        final SQLParserContext context = new SQLParserContext(dataSource, syntaxManager, ruleManager, document);
        context.setPreferenceStore(preferences);
        return context;
    }
}
