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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.exportnow.ExportCommandParser.ParsedCommand;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.sql.SQLControlCommand;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.sql.parser.SQLScriptParser;

/**
 * Finds {@code @exportnow} blocks in a SQL script using DBeaver's own script parser, which
 * understands dialect string escapes, line and block comments, custom delimiters, {@code BEGIN/END}
 * blocks — and both command forms: single-line {@code @exportnow ...} and multiline
 * {@code @@exportnow ... @@}. The query of a "classic" block (no inline SQL) is the next parsed
 * statement. A block that cannot export (malformed JSON, no type, no query) is reported as a {@link
 * Problem} with its line, never silently dropped.
 */
final class ExportBlockScanner {

  static final String NO_QUERY =
      "no query to export: put it after the JSON on the same line, or as the next statement";

  private static final Log log = Log.getLog(ExportBlockScanner.class);

  /** One export block: the offset of its command and the export it describes. */
  record Block(int offset, @NotNull ExportRequest request) {}

  /** A block that cannot export: its line (1-based) and why. */
  record Problem(int line, @NotNull String reason) {

    @NotNull
    @Override
    public String toString() {
      return line == ExportRequest.UNKNOWN_LINE ? reason : "line " + line + ": " + reason;
    }
  }

  /** What a scan found: the exportable blocks and the blocks that cannot export. */
  record Scan(@NotNull List<Block> blocks, @NotNull List<Problem> problems) {

    static final Scan NOTHING = new Scan(List.of(), List.of());

    boolean isEmpty() {
      return blocks.isEmpty() && problems.isEmpty();
    }
  }

  private final ExportCommandParser commandParser = new ExportCommandParser();
  private final SQLParserContextFactory parserContextFactory = new SQLParserContextFactory();

  /** Scans the whole text. */
  @NotNull
  Scan scan(@Nullable DBPDataSource dataSource, @NotNull String text) {
    return scan(dataSource, text, new TextRange(0, text.length()));
  }

  /**
   * Scans only blocks whose command starts inside the range. The block's SQL may extend beyond it
   * (DBeaver discards selected text after a control command anyway, so the command position is what
   * counts).
   */
  @NotNull
  Scan scan(@Nullable DBPDataSource dataSource, @NotNull String text, @NotNull TextRange range) {
    // Parse the whole document: a range-bounded parse would cut off the query
    // following a command near the end of a selection.
    final List<SQLScriptElement> elements = parseScript(dataSource, text);
    final SQLDialect dialect = parserContextFactory.dialect(dataSource);
    final List<Block> blocks = new ArrayList<>();
    final List<Problem> problems = new ArrayList<>();
    for (int index = 0; index < elements.size(); index++) {
      if (elements.get(index) instanceof SQLControlCommand command
          && isExportCommandIn(command, range)) {
        final int line = lineOf(text, command.getOffset());
        try {
          final ParsedCommand parsed = commandParser.parseParameters(command.getParameter());
          blockSql(parsed, elements, index, dialect)
              .ifPresentOrElse(
                  sql ->
                      blocks.add(
                          new Block(
                              command.getOffset(), new ExportRequest(parsed.config(), sql, line))),
                  () -> problems.add(new Problem(line, NO_QUERY)));
        } catch (DBException e) {
          problems.add(new Problem(line, e.getMessage()));
        }
      }
    }
    return new Scan(List.copyOf(blocks), List.copyOf(problems));
  }

  @NotNull
  private List<SQLScriptElement> parseScript(
      @Nullable DBPDataSource dataSource, @NotNull String text) {
    try {
      return SQLScriptParser.extractScriptQueries(
          parserContextFactory.create(dataSource, text), 0, text.length(), true, false, false);
    } catch (Exception e) {
      log.warn("@exportnow: can't parse script", e);
      return List.of();
    }
  }

  private boolean isExportCommandIn(@NotNull SQLControlCommand command, @NotNull TextRange range) {
    return SQLCommandExportNow.COMMAND_ID.equals(command.getCommandId())
        && range.contains(command.getOffset());
  }

  /** 1-based line of a text offset. */
  static int lineOf(@NotNull String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /** The command's inline SQL, else the next parsed statement (the "classic" form). */
  @NotNull
  private Optional<String> blockSql(
      @NotNull ParsedCommand parsed,
      @NotNull List<SQLScriptElement> elements,
      int index,
      @NotNull SQLDialect dialect) {
    if (parsed.inlineSql().isPresent()) {
      return parsed.inlineSql();
    }
    if (index + 1 < elements.size() && elements.get(index + 1) instanceof SQLQuery nextQuery) {
      // the parsed element may keep leading comment lines - not needed for the export
      final String sql = SQLUtils.stripComments(dialect, nextQuery.getText()).trim();
      return Optional.of(sql).filter(text -> !text.isBlank());
    }
    return Optional.empty();
  }
}
