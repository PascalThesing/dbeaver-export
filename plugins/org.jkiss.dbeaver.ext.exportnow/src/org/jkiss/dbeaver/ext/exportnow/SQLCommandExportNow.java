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

import org.eclipse.core.runtime.jobs.JobGroup;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
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
import org.jkiss.utils.CommonUtils;

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

    /** Identity of the scope already exported in this execution run (re-arms when the document/selection changes). */
    private static final String RUN_FLAG = "exportnow.executed";

    private final ExportCommandParser commandParser = new ExportCommandParser();
    private final EditorScope editorScope = new EditorScope();
    private final ExportBlockScanner blockScanner = new ExportBlockScanner();

    @NotNull
    @Override
    public SQLControlResult handleCommand(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLControlCommand command,
        @NotNull SQLScriptContext scriptContext
    ) throws DBException {
        // Validate this command's own JSON regardless of dedup, so syntax errors surface
        final ExportCommandParser.ParsedCommand parsed = commandParser.parseParameters(command.getParameter());
        if (scriptContext.getExecutionContext() == null) {
            throw new DBException("@exportnow: no active database connection");
        }

        final EditorScope.Scope scope = editorScope.resolve(scriptContext, command.getParameter());

        // Only the first @exportnow of an execution run performs the scan. The flag stores
        // the scope identity so a reused script context re-arms when the document changes.
        final Object runId = scope == null ? Boolean.TRUE
            : scope.text().length() + ":" + scope.text().hashCode() + ":" + scope.selectionOffset() + ":" + scope.selectionLength();
        if (runId.equals(scriptContext.getData(RUN_FLAG))) {
            return SQLControlResult.success();
        }
        scriptContext.setData(RUN_FLAG, runId);

        if (scope == null) {
            // No document reachable: export at least this command's inline SQL
            if (parsed.sql() != null) {
                scheduleExport(scriptContext, parsed.params(), parsed.sql(), null);
            } else {
                throw new DBException("@exportnow: cannot determine the query to export (no editor document available)");
            }
            return SQLControlResult.success();
        }

        final DBPDataSource dataSource = dataSource(scriptContext);
        final List<ExportBlockScanner.Block> blocks = scope.selectionOffset() >= 0
            ? blockScanner.findBlocks(dataSource, scope.text(), scope.selectionOffset(), scope.selectionLength())
            : blockScanner.findBlocks(dataSource, scope.text());
        if (blocks.isEmpty()) {
            if (parsed.sql() != null) {
                scheduleExport(scriptContext, parsed.params(), parsed.sql(), null);
                return SQLControlResult.success();
            }
            throw new DBException("@exportnow: no query found to export");
        }
        final JobGroup group = createJobGroup(parsed.params(), blocks.size());
        for (ExportBlockScanner.Block block : blocks) {
            scheduleExport(scriptContext, block.command().params(), block.sql(), group);
        }
        return SQLControlResult.success();
    }

    /**
     * DBeaver's own maxJobCount semantics: cap how many exports of this run execute
     * simultaneously (protects production servers). The key is read from the JSON of
     * the command that triggered the run, not per block; absent = unlimited.
     */
    @Nullable
    JobGroup createJobGroup(@NotNull Map<String, Object> params, int blockCount) {
        final int maxJobCount = CommonUtils.toInt(params.get("maxJobCount"), 0);
        return maxJobCount > 0 && blockCount > 1
            ? new JobGroup("@exportnow exports", maxJobCount, blockCount)
            : null;
    }

    @Nullable
    private DBPDataSource dataSource(@NotNull SQLScriptContext scriptContext) {
        return scriptContext.getExecutionContext() == null
            ? null : scriptContext.getExecutionContext().getDataSource();
    }

    private void scheduleExport(
        @NotNull SQLScriptContext scriptContext,
        @NotNull Map<String, Object> params,
        @NotNull String sql,
        @Nullable JobGroup group
    ) {
        final SQLQuery query = new SQLQuery(dataSource(scriptContext), sql);
        final SQLQueryDataContainer container =
            new SQLQueryDataContainer(scriptContext::getExecutionContext, query, scriptContext, log);
        final HeadlessExportJob job = new HeadlessExportJob(container, params);
        if (group != null) {
            job.setJobGroup(group);
        }
        job.schedule();
    }
}
