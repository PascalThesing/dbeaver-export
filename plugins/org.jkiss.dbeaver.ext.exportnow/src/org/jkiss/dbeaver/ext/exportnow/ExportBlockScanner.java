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

import org.jkiss.code.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure text scanner for {@code @exportnow} blocks in a SQL script:
 * <ul>
 * <li>inline: {@code @exportnow {json} select ...;} on one line</li>
 * <li>multiline: {@code @@exportnow {json} <sql...> @@}</li>
 * <li>classic: {@code @exportnow {json};} followed (after any blank/comment
 *     lines) by the next statement up to an unquoted {@code ;}</li>
 * </ul>
 */
final class ExportBlockScanner {

    /** One export block: raw command parameter (JSON [+ inline sql]) and the resolved SQL. */
    record Block(int offset, @NotNull String parameter, @NotNull String sql) {
    }

    private ExportBlockScanner() {
    }

    /** Scans the whole text. */
    @NotNull
    static List<Block> findBlocks(@NotNull String text) {
        return findBlocks(text, 0, text.length());
    }

    /**
     * Scans only blocks whose command starts inside {@code [offset, offset+length)}.
     * The block's SQL may extend beyond the range (DBeaver discards selected text
     * after a control command anyway, so the command position is what counts).
     */
    @NotNull
    static List<Block> findBlocks(@NotNull String text, int offset, int length) {
        final List<Block> blocks = new ArrayList<>();
        final int rangeEnd = offset + length;
        int pos = 0;
        while (pos < text.length()) {
            final int lineEnd = endOfLine(text, pos);
            final String line = text.substring(pos, lineEnd);
            final String trimmed = line.stripLeading();
            final int cmdOffset = pos + (line.length() - trimmed.length());

            if (trimmed.startsWith("@@exportnow ") || trimmed.startsWith("@@exportnow\t")) {
                // multiline block: up to the closing @@
                final int bodyStart = cmdOffset + "@@exportnow ".length();
                final int close = text.indexOf("@@", bodyStart);
                final int bodyEnd = close < 0 ? text.length() : close;
                if (cmdOffset >= offset && cmdOffset < rangeEnd) {
                    addBlock(blocks, text, cmdOffset, text.substring(bodyStart, bodyEnd), -1);
                }
                pos = (close < 0 ? text.length() : close + 2);
                continue;
            }
            if (trimmed.startsWith("@exportnow ") || trimmed.startsWith("@exportnow\t")) {
                final String parameter = trimmed.substring("@exportnow ".length());
                if (cmdOffset >= offset && cmdOffset < rangeEnd) {
                    addBlock(blocks, text, cmdOffset, parameter, lineEnd);
                }
            }
            pos = lineEnd + 1;
        }
        return blocks;
    }

    /**
     * parameter = text after the command word. If it contains inline SQL after the
     * JSON, that's the query; otherwise (classic form) the next statement after
     * {@code searchFrom} is the query. Blocks without a resolvable query are skipped.
     */
    private static void addBlock(List<Block> blocks, String text, int cmdOffset, String parameter, int searchFrom) {
        String sql = null;
        try {
            final var parsed = SQLCommandExportNow.parseParameters(parameter);
            sql = parsed.sql();
        } catch (Exception e) {
            return; // malformed block: the command handler reports it when executed
        }
        if (sql == null && searchFrom >= 0) {
            sql = nextStatement(text, searchFrom);
        }
        if (sql != null && !sql.isEmpty()) {
            blocks.add(new Block(cmdOffset, parameter, sql));
        }
    }

    /** Next statement after pos: skips blank lines and line comments, reads to an unquoted ';'. */
    private static String nextStatement(String text, int pos) {
        int i = pos;
        while (i < text.length()) {
            final int lineEnd = endOfLine(text, i);
            final String trimmed = text.substring(i, lineEnd).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                i = lineEnd + 1;
                continue;
            }
            if (trimmed.startsWith("@")) {
                return null; // next block starts before any statement
            }
            break;
        }
        if (i >= text.length()) {
            return null;
        }
        // read to unquoted ';'
        boolean inSingle = false;
        boolean inDouble = false;
        for (int j = i; j < text.length(); j++) {
            final char c = text.charAt(j);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == ';') {
                return text.substring(i, j).trim();
            }
        }
        final String rest = text.substring(i).trim();
        return rest.isEmpty() ? null : rest;
    }

    private static int endOfLine(String text, int from) {
        final int nl = text.indexOf('\n', from);
        return nl < 0 ? text.length() : nl;
    }
}
