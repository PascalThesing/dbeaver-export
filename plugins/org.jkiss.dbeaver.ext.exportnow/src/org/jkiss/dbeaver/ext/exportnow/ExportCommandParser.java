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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.utils.CommonUtils;

/**
 * Parses the {@code @exportnow} command parameter: a leading JSON config object, optionally
 * followed by an inline SQL query.
 */
final class ExportCommandParser {

  private static final int NOT_FOUND = -1;
  private static final String SYNTAX_ERROR =
      "Invalid syntax. Use '@exportnow {\"type\": <type>, \"producer\": {...}, \"consumer\": {...},"
          + " \"processor\": {...}} [<query>]'";

  /** Parsed command parameter: the export settings plus the optional inline SQL. */
  record ParsedCommand(@NotNull ExportConfig config, @NotNull Optional<String> inlineSql) {}

  private final Gson gson = new Gson();
  private final JsonFactory jsonFactory = new JsonFactory();

  /** Splits the parameter into JSON settings and inline SQL, and validates the mandatory type. */
  @NotNull
  ParsedCommand parseParameters(@Nullable String parameter) throws DBException {
    final String text = CommonUtils.notEmpty(parameter).trim();
    final int jsonEnd = findJsonEnd(text);
    if (jsonEnd == NOT_FOUND) {
      throw new DBException(SYNTAX_ERROR);
    }
    final ExportConfig config = new ExportConfig(parseJson(text.substring(0, jsonEnd)));
    if (CommonUtils.isEmpty(config.type())) {
      throw new DBException("`type` attribute is mandatory for @exportnow");
    }
    final String sql = stripTrailingSemicolons(text.substring(jsonEnd));
    return new ParsedCommand(config, Optional.of(sql).filter(inline -> !inline.isEmpty()));
  }

  @NotNull
  private Map<String, Object> parseJson(@NotNull String json) throws DBException {
    try {
      return JSONUtils.parseMap(gson, new StringReader(json));
    } catch (Exception e) {
      throw new DBException(SYNTAX_ERROR, e);
    }
  }

  /** DBeaver's script parser includes the trailing {@code ;} in the command parameter. */
  @NotNull
  private String stripTrailingSemicolons(@NotNull String sql) {
    String result = sql.trim();
    while (result.endsWith(";")) {
      result = result.substring(0, result.length() - 1).trim();
    }
    return result;
  }

  /** Index just past the closing brace of the leading JSON object, or {@link #NOT_FOUND}. */
  private int findJsonEnd(@NotNull String text) {
    if (!text.startsWith("{")) {
      return NOT_FOUND;
    }
    try (JsonParser parser = jsonFactory.createParser(text)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        return NOT_FOUND;
      }
      parser.skipChildren();
      return (int) parser.currentTokenLocation().getCharOffset() + 1;
    } catch (IOException e) {
      return NOT_FOUND;
    }
  }
}
