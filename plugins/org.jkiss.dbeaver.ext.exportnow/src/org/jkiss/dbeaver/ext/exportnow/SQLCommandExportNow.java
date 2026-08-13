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
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
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
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final Log log = Log.getLog(SQLCommandExportNow.class);

    private static final String RUN_FLAG = "exportnow.executed";

    /** Parsed command parameter: transfer settings plus optional inline SQL. */
    record ParsedCommand(@NotNull Map<String, Object> params, @Nullable String sql) {
    }

    /** Editor scope: full document text plus the selected range (or -1/-1). */
    record Scope(@NotNull String text, int selectionOffset, int selectionLength) {
    }

    @NotNull
    @Override
    public SQLControlResult handleCommand(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLControlCommand command,
        @NotNull SQLScriptContext scriptContext
    ) throws DBException {
        // Validate this command's own JSON regardless of dedup, so syntax errors surface
        final ParsedCommand parsed = parseParameters(command.getParameter());

        // Only the first @exportnow of an execution run performs the scan
        if (Boolean.TRUE.equals(scriptContext.getData(RUN_FLAG))) {
            return SQLControlResult.success();
        }
        scriptContext.setData(RUN_FLAG, Boolean.TRUE);

        final Scope scope = resolveScope(scriptContext);
        if (scope == null) {
            // No document reachable: export at least this command's inline SQL
            if (parsed.sql() != null) {
                scheduleExport(scriptContext, parsed.params(), parsed.sql());
            } else {
                throw new DBException("@exportnow: cannot determine the query to export (no editor document available)");
            }
            return SQLControlResult.success();
        }

        final List<ExportBlockScanner.Block> blocks = scope.selectionOffset() >= 0
            ? ExportBlockScanner.findBlocks(scope.text(), scope.selectionOffset(), scope.selectionLength())
            : ExportBlockScanner.findBlocks(scope.text());
        if (blocks.isEmpty() && parsed.sql() != null) {
            scheduleExport(scriptContext, parsed.params(), parsed.sql());
            return SQLControlResult.success();
        }
        for (ExportBlockScanner.Block block : blocks) {
            final ParsedCommand blockCommand = parseParameters(block.parameter());
            scheduleExport(scriptContext, blockCommand.params(), block.sql());
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

    /** Live editor document + selection (UI thread); falls back to the saved source file. */
    @Nullable
    private static Scope resolveScope(@NotNull SQLScriptContext scriptContext) {
        final Scope[] result = new Scope[1];
        try {
            PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
                final IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                final IEditorPart editorPart = window == null || window.getActivePage() == null
                    ? null : window.getActivePage().getActiveEditor();
                final ITextEditor editor = editorPart == null ? null : editorPart.getAdapter(ITextEditor.class);
                if (editor == null) {
                    return;
                }
                final IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                if (document == null) {
                    return;
                }
                int selOffset = -1;
                int selLength = -1;
                final ISelection selection = editor.getSelectionProvider().getSelection();
                if (selection instanceof ITextSelection textSelection && textSelection.getLength() > 0) {
                    selOffset = textSelection.getOffset();
                    selLength = textSelection.getLength();
                }
                result[0] = new Scope(document.get(), selOffset, selLength);
            });
        } catch (Throwable e) {
            log.debug("Can't access editor document, falling back to source file", e);
        }
        if (result[0] != null) {
            return result[0];
        }
        final Path sourceFile = scriptContext.getSourceFile();
        if (sourceFile != null && Files.isReadable(sourceFile)) {
            try {
                return new Scope(Files.readString(sourceFile), -1, -1);
            } catch (Exception e) {
                log.debug("Can't read source file " + sourceFile, e);
            }
        }
        return null;
    }

    /**
     * Parses the command parameter: strips trailing statement separators (the SQL parser
     * includes the trailing {@code ;} in the parameter) and validates the mandatory type.
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
