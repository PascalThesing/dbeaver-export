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
import com.fasterxml.jackson.core.JsonToken;
import com.google.gson.Gson;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

/**
 * Parses the {@code @exportnow} command parameter: a leading JSON config object,
 * optionally followed by an inline SQL query.
 */
final class ExportCommandParser {

    private static final Gson GSON = new Gson();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static final String SYNTAX_ERROR =
        "Invalid syntax. Use '@exportnow {\"type\": <type>, \"producer\": {...}, \"consumer\": {...}, \"processor\": {...}} [<query>]'";

    /** Parsed command parameter: transfer settings plus optional inline SQL. */
    record ParsedCommand(@NotNull Map<String, Object> params, @Nullable String sql) {
    }

    private ExportCommandParser() {
    }

    /**
     * Strips trailing statement separators (the SQL parser includes the trailing
     * {@code ;} in the parameter) and validates the mandatory type.
     */
    @NotNull
    static ParsedCommand parseParameters(@Nullable String parameter) throws DBException {
        final String text = CommonUtils.notEmpty(parameter).trim();
        final int jsonEnd = findJsonEnd(text);
        if (jsonEnd < 0) {
            throw new DBException(SYNTAX_ERROR);
        }
        final String json = text.substring(0, jsonEnd);
        String sql = text.substring(jsonEnd).trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }

        final Map<String, Object> params;
        try {
            params = JSONUtils.parseMap(GSON, new StringReader(json));
        } catch (Exception e) {
            throw new DBException(SYNTAX_ERROR, e);
        }
        if (CommonUtils.isEmpty(JSONUtils.getString(params, HeadlessExportJob.PARAMETER_TYPE))) {
            throw new DBException("`type` attribute is mandatory for @exportnow");
        }
        return new ParsedCommand(params, sql.isEmpty() ? null : sql);
    }

    /** Returns the index just past the closing brace of the leading JSON object, or -1. */
    private static int findJsonEnd(@NotNull String text) {
        if (!text.startsWith("{")) {
            return -1;
        }
        try (com.fasterxml.jackson.core.JsonParser parser = JSON_FACTORY.createParser(text)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return -1;
            }
            parser.skipChildren();
            return (int) parser.currentTokenLocation().getCharOffset() + 1;
        } catch (IOException e) {
            return -1;
        }
    }
}
