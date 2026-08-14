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

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the text scope an {@code @exportnow} command applies to: the live
 * document (and selection) of the active SQL editor, falling back to the
 * script's saved source file.
 */
public final class EditorScope {

    private static final Log log = Log.getLog(EditorScope.class);

    /** Full document text plus the selected range (or -1/-1 when nothing is selected). */
    public record Scope(@NotNull String text, int selectionOffset, int selectionLength) {
    }

    /**
     * Live editor document + selection (fetched on the UI thread), falling back to the
     * saved source file. {@code commandParameter}, when given, guards against resolving
     * the wrong editor: if the active editor's document does not contain the executing
     * command's parameter (the user switched tabs mid-run), it is ignored.
     */
    @Nullable
    public Scope resolve(@Nullable SQLScriptContext scriptContext, @Nullable String commandParameter) {
        Scope scope = resolveActiveEditor();
        if (scope != null
            && commandParameter != null && !commandParameter.isBlank()
            && !scope.text().contains(firstLine(commandParameter))) {
            log.debug("Active editor does not contain the executing @exportnow command - ignoring it");
            scope = null;
        }
        if (scope != null) {
            return scope;
        }
        final Path sourceFile = scriptContext == null ? null : scriptContext.getSourceFile();
        if (sourceFile != null && Files.isReadable(sourceFile)) {
            try {
                return new Scope(Files.readString(sourceFile), -1, -1);
            } catch (Exception e) {
                log.debug("Can't read source file " + sourceFile, e);
            }
        }
        return null;
    }

    @Nullable
    private Scope resolveActiveEditor() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return null;
        }
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
        return result[0];
    }

    @NotNull
    private String firstLine(@NotNull String text) {
        final int nl = text.indexOf('\n');
        return (nl < 0 ? text : text.substring(0, nl)).trim();
    }
}
