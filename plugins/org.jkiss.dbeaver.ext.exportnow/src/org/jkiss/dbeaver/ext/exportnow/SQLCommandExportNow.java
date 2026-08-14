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
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLControlCommand;
import org.jkiss.dbeaver.model.sql.SQLControlCommandHandler;
import org.jkiss.dbeaver.model.sql.SQLControlResult;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.data.SQLQueryDataContainer;

import java.util.List;
import java.util.Map;

/**
 * Handler for the {@code @exportnow} control command. Document-driven: when an
 * {@code @exportnow} command executes (once per execution run), the current
 * scope — the editor text selection if present, else the whole document — is
 * scanned for export blocks and every found block is exported headlessly in a
 * background job. The script itself continues normally; a selection of plain
 * statements (no command) never triggers an export.
 */
public class SQLCommandExportNow implements SQLControlCommandHandler {

    static final String COMMAND_ID = "exportnow";

    private static final Log log = Log.getLog(SQLCommandExportNow.class);

    private static final String RUN_FLAG = "exportnow.executed";

    @NotNull
    @Override
    public SQLControlResult handleCommand(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLControlCommand command,
        @NotNull SQLScriptContext scriptContext
    ) throws DBException {
        // Validate this command's own JSON regardless of dedup, so syntax errors surface
        final ExportCommandParser.ParsedCommand parsed = ExportCommandParser.parseParameters(command.getParameter());

        // Only the first @exportnow of an execution run performs the scan
        if (Boolean.TRUE.equals(scriptContext.getData(RUN_FLAG))) {
            return SQLControlResult.success();
        }
        scriptContext.setData(RUN_FLAG, Boolean.TRUE);

        final EditorScope.Scope scope = EditorScope.resolve(scriptContext, command.getParameter());
        if (scope == null) {
            // No document reachable: export at least this command's inline SQL
            if (parsed.sql() != null) {
                scheduleExport(scriptContext, parsed.params(), parsed.sql());
            } else {
                throw new DBException("@exportnow: cannot determine the query to export (no editor document available)");
            }
            return SQLControlResult.success();
        }

        final DBPDataSource dataSource = scriptContext.getExecutionContext() == null
            ? null : scriptContext.getExecutionContext().getDataSource();
        final List<ExportBlockScanner.Block> blocks = scope.selectionOffset() >= 0
            ? ExportBlockScanner.findBlocks(dataSource, scope.text(), scope.selectionOffset(), scope.selectionLength())
            : ExportBlockScanner.findBlocks(dataSource, scope.text());
        if (blocks.isEmpty() && parsed.sql() != null) {
            scheduleExport(scriptContext, parsed.params(), parsed.sql());
            return SQLControlResult.success();
        }
        for (ExportBlockScanner.Block block : blocks) {
            scheduleExport(scriptContext, block.command().params(), block.sql());
        }
        return SQLControlResult.success();
    }

    private static void scheduleExport(
        @NotNull SQLScriptContext scriptContext,
        @NotNull Map<String, Object> params,
        @NotNull String sql
    ) {
        final SQLQuery query = new SQLQuery(
            scriptContext.getExecutionContext() == null ? null : scriptContext.getExecutionContext().getDataSource(),
            sql);
        final SQLQueryDataContainer container =
            new SQLQueryDataContainer(scriptContext::getExecutionContext, query, scriptContext, log);
        new HeadlessExportJob(container, params).schedule();
    }
}
