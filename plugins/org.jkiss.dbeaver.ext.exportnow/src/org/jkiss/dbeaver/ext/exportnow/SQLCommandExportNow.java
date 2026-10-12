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
import org.eclipse.core.runtime.jobs.JobGroup;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.exportnow.EditorScope.Scope;
import org.jkiss.dbeaver.ext.exportnow.ExportBlockScanner.Block;
import org.jkiss.dbeaver.ext.exportnow.ExportCommandParser.ParsedCommand;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLControlCommand;
import org.jkiss.dbeaver.model.sql.SQLControlCommandHandler;
import org.jkiss.dbeaver.model.sql.SQLControlResult;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.data.SQLQueryDataContainer;

/**
 * Handler for the {@code @exportnow} control command. Document-driven: when an {@code @exportnow}
 * command executes (once per execution run), the current scope — the editor text selection if
 * present, else the whole document — is scanned for export blocks and every found block is exported
 * headlessly in a background job. The script itself continues normally; a selection of plain
 * statements (no command) never triggers an export.
 */
public class SQLCommandExportNow implements SQLControlCommandHandler {

  static final String COMMAND_ID = "exportnow";

  private static final Log log = Log.getLog(SQLCommandExportNow.class);

  /** Identity of the scope already exported in this execution run. */
  private static final String RUN_FLAG = "exportnow.executed";

  private static final String NO_SCOPE = "no-scope";

  private final ExportCommandParser commandParser = new ExportCommandParser();
  private final EditorScope editorScope = new EditorScope();
  private final ExportBlockScanner blockScanner = new ExportBlockScanner();
  private final ExportVariables variables = new ExportVariables();
  private final ExportNotifications notifications = new ExportNotifications();

  @NotNull
  @Override
  public SQLControlResult handleCommand(
      @NotNull DBRProgressMonitor monitor,
      @NotNull SQLControlCommand command,
      @NotNull SQLScriptContext scriptContext)
      throws DBException {
    // Validate this command's own JSON regardless of dedup, so syntax errors surface
    final ParsedCommand parsed = commandParser.parseParameters(command.getParameter());
    if (scriptContext.getExecutionContext() == null) {
      throw new DBException("@exportnow: no active database connection");
    }
    final Optional<Scope> scope = editorScope.resolve(scriptContext, command.getParameter());
    if (!startsNewRun(scriptContext, scope)) {
      return SQLControlResult.success();
    }
    final List<ExportRequest> requests =
        scope.map(s -> findRequests(s, scriptContext)).orElse(List.of());
    final List<ExportRequest> exports =
        requests.isEmpty() ? List.of(inlineRequest(parsed, scope.isPresent())) : requests;
    scheduleExports(scriptContext, exports, createJobGroup(parsed.config(), exports.size()));
    return SQLControlResult.success();
  }

  /**
   * DBeaver's own maxJobCount semantics: cap how many exports of this run execute simultaneously
   * (protects production servers). The key is read from the JSON of the command that triggered the
   * run, not per block; absent = unlimited.
   */
  @Nullable
  JobGroup createJobGroup(@NotNull ExportConfig config, int exportCount) {
    final int maxJobCount = config.maxJobCount();
    return maxJobCount > 0 && exportCount > 1
        ? new JobGroup("@exportnow exports", maxJobCount, exportCount)
        : null;
  }

  /**
   * Only the first {@code @exportnow} of an execution run exports. The run is identified by the
   * scope, so a reused script context starts a new run when the document or selection changes.
   */
  private boolean startsNewRun(
      @NotNull SQLScriptContext scriptContext, @NotNull Optional<Scope> scope) {
    final String runId = scope.map(Scope::identity).orElse(NO_SCOPE);
    if (runId.equals(scriptContext.getData(RUN_FLAG))) {
      return false;
    }
    scriptContext.setData(RUN_FLAG, runId);
    return true;
  }

  @NotNull
  private List<ExportRequest> findRequests(
      @NotNull Scope scope, @NotNull SQLScriptContext scriptContext) {
    return blockScanner.findBlocks(dataSource(scriptContext), scope.text(), scope.range()).stream()
        .map(Block::request)
        .toList();
  }

  /** No document reachable or no block found: export at least this command's inline SQL. */
  @NotNull
  private ExportRequest inlineRequest(@NotNull ParsedCommand parsed, boolean hasDocument)
      throws DBException {
    final String sql =
        parsed
            .inlineSql()
            .orElseThrow(
                () ->
                    new DBException(
                        hasDocument
                            ? "@exportnow: no query found to export"
                            : "@exportnow: cannot determine the query to export (no editor"
                                + " document available)"));
    return new ExportRequest(parsed.config(), sql);
  }

  /**
   * Resolves variables and parameters of all exports of the run at once (a single parameters
   * dialog), then starts one background job per export.
   */
  private void scheduleExports(
      @NotNull SQLScriptContext scriptContext,
      @NotNull List<ExportRequest> requests,
      @Nullable JobGroup group)
      throws DBException {
    variables
        .resolve(scriptContext, dataSource(scriptContext), requests)
        .ifPresentOrElse(
            resolved -> resolved.forEach(request -> scheduleExport(scriptContext, request, group)),
            notifications::runCancelled);
  }

  private void scheduleExport(
      @NotNull SQLScriptContext scriptContext,
      @NotNull ExportRequest request,
      @Nullable JobGroup group) {
    final SQLQuery query = new SQLQuery(dataSource(scriptContext), request.sql());
    final SQLQueryDataContainer container =
        new SQLQueryDataContainer(scriptContext::getExecutionContext, query, scriptContext, log);
    final HeadlessExportJob job = new HeadlessExportJob(container, request.config());
    if (group != null) {
      job.setJobGroup(group);
    }
    job.schedule();
  }

  @Nullable
  private DBPDataSource dataSource(@NotNull SQLScriptContext scriptContext) {
    return scriptContext.getExecutionContext() == null
        ? null
        : scriptContext.getExecutionContext().getDataSource();
  }
}
