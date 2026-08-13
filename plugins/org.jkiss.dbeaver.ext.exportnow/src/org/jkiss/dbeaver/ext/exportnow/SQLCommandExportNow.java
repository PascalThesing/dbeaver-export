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

import com.google.gson.Gson;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLControlCommand;
import org.jkiss.dbeaver.model.sql.SQLControlCommandHandler;
import org.jkiss.dbeaver.model.sql.SQLControlResult;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.data.SQLQueryDataContainer;
import org.jkiss.utils.CommonUtils;

import java.io.StringReader;
import java.util.Map;

/**
 * Handler for the {@code @exportnow} control command. Two forms:
 * <ul>
 * <li>{@code @exportnow {json} select ...;} — the SQL shares the statement
 *     (one line, or a {@code @@exportnow ... @@} multiline block): the export
 *     of that query starts immediately in a background job.</li>
 * <li>{@code @exportnow {json}} alone — like the built-in {@code @export}, the
 *     next executed query (any number of lines below) is exported.</li>
 * </ul>
 * Either way the transfer runs headlessly, the script continues, and multiple
 * exports in one script run in parallel, each allowed to fail independently.
 */
public class SQLCommandExportNow implements SQLControlCommandHandler {

    static final String PRAGMA_EXPORT_NOW = "exportnow";

    private static final Log log = Log.getLog(SQLCommandExportNow.class);

    /** Parsed command parameter: transfer settings plus optional inline SQL. */
    record ParsedCommand(@NotNull Map<String, Object> params, @Nullable String sql) {
    }

    @NotNull
    @Override
    public SQLControlResult handleCommand(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLControlCommand command,
        @NotNull SQLScriptContext scriptContext
    ) throws DBException {
        final ParsedCommand parsed = parseParameters(command.getParameter());

        if (parsed.sql() != null) {
            // Inline form: export the SQL contained in this very statement
            final SQLQuery query = new SQLQuery(
                scriptContext.getExecutionContext() == null ? null : scriptContext.getExecutionContext().getDataSource(),
                parsed.sql());
            final SQLQueryDataContainer container =
                new SQLQueryDataContainer(scriptContext::getExecutionContext, query, scriptContext, log);
            new HeadlessExportJob(container, parsed.params()).schedule();
        } else {
            // Classic form: the next executed query is exported
            scriptContext.setPragma(PRAGMA_EXPORT_NOW, parsed.params());
        }

        return SQLControlResult.success();
    }

    /**
     * Splits the command parameter into the leading JSON object and an optional
     * trailing SQL query, stripping trailing statement separators (the SQL
     * parser includes the trailing {@code ;} in the parameter).
     */
    @NotNull
    static ParsedCommand parseParameters(@Nullable String parameter) throws DBException {
        final String text = CommonUtils.notEmpty(parameter).trim();
        final int jsonEnd = findJsonEnd(text);
        if (jsonEnd < 0) {
            throw new DBException(
                "Invalid syntax. Use '@exportnow {\"type\": <type>, \"producer\": {...}, \"consumer\": {...}, \"processor\": {...}} [<query>]'");
        }
        final String json = text.substring(0, jsonEnd);
        String sql = text.substring(jsonEnd).trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }

        final Map<String, Object> params;
        try {
            params = JSONUtils.parseMap(new Gson(), new StringReader(json));
        } catch (Exception e) {
            throw new DBException(
                "Invalid syntax. Use '@exportnow {\"type\": <type>, \"producer\": {...}, \"consumer\": {...}, \"processor\": {...}} [<query>]'", e);
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
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }
}
