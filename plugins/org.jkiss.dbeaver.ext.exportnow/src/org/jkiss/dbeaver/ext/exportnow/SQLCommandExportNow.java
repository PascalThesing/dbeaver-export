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
import java.util.stream.Collectors;
import org.eclipse.core.runtime.jobs.JobGroup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.exportnow.EditorScope.Scope;
import org.jkiss.dbeaver.ext.exportnow.ExportBlockScanner.Block;
import org.jkiss.dbeaver.ext.exportnow.ExportBlockScanner.Problem;
import org.jkiss.dbeaver.ext.exportnow.ExportBlockScanner.Scan;
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
 *
 * <p>Before any export starts, every block of the run is checked. One bad block — malformed JSON,
 * no query, unknown format, preset or data-format profile — aborts the whole run: no export starts,
 * and the error names every bad block with its line.
 */
public class SQLCommandExportNow implements SQLControlCommandHandler {

  static final String COMMAND_ID = "exportnow";

  private static final Log log = Log.getLog(SQLCommandExportNow.class);

  /** Identity of the scope already exported in this execution run. */
  private static final String RUN_FLAG = "exportnow.executed";

  private static final String NO_SCOPE = "no-scope";
  private static final String INSTALL_NEW_SOFTWARE_COMMAND =
      "org.eclipse.equinox.p2.ui.sdk.install";

  private final ExportCommandParser commandParser = new ExportCommandParser();
  private final EditorScope editorScope = new EditorScope();
  private final ExportBlockScanner blockScanner = new ExportBlockScanner();
  private final ExportVariables variables = new ExportVariables();
  private final TransferSettingsFactory settingsFactory = new TransferSettingsFactory();
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
    // the first trace of an execution: without it in the log, DBeaver never called the plugin
    log.info(
        "@exportnow: command executed, scanning "
            + scope.map(SQLCommandExportNow::describe).orElse("no editor document"));
    if (!startsNewRun(scriptContext, scope)) {
      log.debug("@exportnow: this run's exports are already prepared");
      return SQLControlResult.success();
    }
    final Scan scan =
        scope
            .map(s -> blockScanner.scan(dataSource(scriptContext), s.text(), s.range()))
            .orElse(Scan.NOTHING);
    scan.blocks()
        .forEach(block -> log.info("@exportnow: export block at line " + block.request().line()));
    scan.problems().forEach(problem -> log.warn("@exportnow: bad block, " + problem));
    final List<ExportRequest> requests =
        scan.isEmpty()
            ? List.of(inlineRequest(parsed, scope.isPresent()))
            : scan.blocks().stream().map(Block::request).toList();
    final Optional<List<ExportRequest>> resolved =
        variables.resolve(scriptContext, dataSource(scriptContext), requests);
    if (resolved.isEmpty()) {
      notifications.runCancelled();
      return SQLControlResult.success();
    }
    final List<Problem> problems = new ArrayList<>(scan.problems());
    problems.addAll(validate(resolved.get()));
    if (!problems.isEmpty()) {
      // Retry in DBeaver's error dialog (or the next @exportnow) must check again, not pass
      // silently
      scriptContext.setData(RUN_FLAG, null);
      throw abort(problems);
    }
    scheduleExports(
        scriptContext, resolved.get(), createJobGroup(parsed.config(), resolved.get().size()));
    log.info("@exportnow: " + resolved.get().size() + " export(s) started");
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

  /** Every request whose settings would fail at export time, with its line. */
  @NotNull
  List<Problem> validate(@NotNull List<ExportRequest> requests) {
    final List<Problem> problems = new ArrayList<>();
    for (ExportRequest request : requests) {
      try {
        settingsFactory.validate(request.config());
      } catch (DBException e) {
        problems.add(new Problem(request.line(), e.getMessage()));
      }
    }
    return problems;
  }

  /**
   * Stops the run: one error notification (clicking it opens Install New Software when a block
   * needs DBeaver's Office extension) and the exception DBeaver shows in the SQL editor.
   */
  @NotNull
  DBException abort(@NotNull List<Problem> problems) {
    final String message =
        "No export started - fix "
            + (problems.size() == 1 ? "this @exportnow block" : "these @exportnow blocks")
            + " first:\n"
            + problems.stream().map(Problem::toString).collect(Collectors.joining("\n"));
    log.error("@exportnow: run aborted. " + message);
    final boolean needsOffice =
        problems.stream()
            .anyMatch(problem -> problem.reason().contains(TransferSettingsFactory.INSTALL_OFFICE));
    notifications.runAborted(
        message, needsOffice ? SQLCommandExportNow::openInstallNewSoftware : null);
    return new DBException(message);
  }

  private static void openInstallNewSoftware() {
    if (!PlatformUI.isWorkbenchRunning()) {
      return;
    }
    PlatformUI.getWorkbench()
        .getDisplay()
        .asyncExec(
            () -> {
              try {
                PlatformUI.getWorkbench()
                    .getService(IHandlerService.class)
                    .executeCommand(INSTALL_NEW_SOFTWARE_COMMAND, null);
              } catch (Exception e) {
                log.warn("@exportnow: can't open Install New Software", e);
              }
            });
  }

  @NotNull
  private static String describe(@NotNull Scope scope) {
    return scope.range().length() == scope.text().length() ? "the whole document" : "the selection";
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
    return new ExportRequest(parsed.config(), sql, ExportRequest.UNKNOWN_LINE);
  }

  /** Starts one background job per export of the run. */
  private void scheduleExports(
      @NotNull SQLScriptContext scriptContext,
      @NotNull List<ExportRequest> requests,
      @Nullable JobGroup group) {
    requests.forEach(request -> scheduleExport(scriptContext, request, group));
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
