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

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
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
 * statement.
 */
final class ExportBlockScanner {

  private static final Log log = Log.getLog(ExportBlockScanner.class);

  /** One export block: the offset of its command and the export it describes. */
  record Block(int offset, @NotNull ExportRequest request) {}

  private final ExportCommandParser commandParser = new ExportCommandParser();
  private final SQLParserContextFactory parserContextFactory = new SQLParserContextFactory();
  private final ExportNotifications notifications = new ExportNotifications();

  /** Scans the whole text. */
  @NotNull
  List<Block> findBlocks(@Nullable DBPDataSource dataSource, @NotNull String text) {
    return findBlocks(dataSource, text, new TextRange(0, text.length()));
  }

  /**
   * Scans only blocks whose command starts inside the range. The block's SQL may extend beyond it
   * (DBeaver discards selected text after a control command anyway, so the command position is what
   * counts).
   */
  @NotNull
  List<Block> findBlocks(
      @Nullable DBPDataSource dataSource, @NotNull String text, @NotNull TextRange range) {
    // Parse the whole document: a range-bounded parse would cut off the query
    // following a command near the end of a selection.
    final List<SQLScriptElement> elements = parseScript(dataSource, text);
    final SQLDialect dialect = parserContextFactory.dialect(dataSource);
    return IntStream.range(0, elements.size())
        .mapToObj(index -> toBlock(elements, index, range, dialect))
        .flatMap(Optional::stream)
        .toList();
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

  @NotNull
  private Optional<Block> toBlock(
      @NotNull List<SQLScriptElement> elements,
      int index,
      @NotNull TextRange range,
      @NotNull SQLDialect dialect) {
    if (!(elements.get(index) instanceof SQLControlCommand command)
        || !isExportCommandIn(command, range)) {
      return Optional.empty();
    }
    return parseOrWarn(command)
        .flatMap(
            parsed ->
                blockSql(parsed, elements, index, dialect)
                    .map(
                        sql ->
                            new Block(
                                command.getOffset(), new ExportRequest(parsed.config(), sql))));
  }

  private boolean isExportCommandIn(@NotNull SQLControlCommand command, @NotNull TextRange range) {
    return SQLCommandExportNow.COMMAND_ID.equals(command.getCommandId())
        && range.contains(command.getOffset());
  }

  @NotNull
  private Optional<ParsedCommand> parseOrWarn(@NotNull SQLControlCommand command) {
    try {
      return Optional.of(commandParser.parseParameters(command.getParameter()));
    } catch (Exception e) {
      log.warn(
          "@exportnow: skipping malformed block at offset "
              + command.getOffset()
              + ": "
              + e.getMessage());
      notifications.blockSkipped(command.getOffset(), e.getMessage());
      return Optional.empty();
    }
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
