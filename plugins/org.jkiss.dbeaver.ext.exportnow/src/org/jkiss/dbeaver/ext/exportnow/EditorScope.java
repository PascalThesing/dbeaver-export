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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.utils.CommonUtils;

/**
 * Resolves the text scope an {@code @exportnow} command applies to: the live document (and
 * selection) of the active SQL editor, falling back to the script's saved source file.
 */
public final class EditorScope {

  private static final Log log = Log.getLog(EditorScope.class);

  /** The full document text and the part to export from: the selection, else the whole text. */
  public record Scope(@NotNull String text, @NotNull TextRange range) {

    /** Identity of the scope; a changed document or selection makes it a new export run. */
    @NotNull
    String identity() {
      return text.length() + ":" + text.hashCode() + ":" + range.offset() + ":" + range.length();
    }
  }

  /**
   * Live editor document + selection (fetched on the UI thread), falling back to the saved source
   * file. {@code commandParameter}, when given, guards against resolving the wrong editor: if the
   * active editor's document does not contain the executing command's parameter (the user switched
   * tabs mid-run), it is ignored.
   */
  @NotNull
  public Optional<Scope> resolve(
      @Nullable SQLScriptContext scriptContext, @Nullable String commandParameter) {
    return activeEditorScope()
        .filter(scope -> containsCommand(scope, commandParameter))
        .or(() -> sourceFileScope(scriptContext));
  }

  private boolean containsCommand(@NotNull Scope scope, @Nullable String commandParameter) {
    if (CommonUtils.isEmptyTrimmed(commandParameter)
        || scope.text().contains(firstLine(commandParameter))) {
      return true;
    }
    log.warn("@exportnow: active editor does not contain the executing command - ignoring it");
    return false;
  }

  @NotNull
  private Optional<Scope> sourceFileScope(@Nullable SQLScriptContext scriptContext) {
    final Path sourceFile = scriptContext == null ? null : scriptContext.getSourceFile();
    if (sourceFile == null || !Files.isReadable(sourceFile)) {
      return Optional.empty();
    }
    try {
      return Optional.of(wholeText(Files.readString(sourceFile)));
    } catch (IOException e) {
      log.debug("Can't read source file " + sourceFile, e);
      return Optional.empty();
    }
  }

  @NotNull
  private Optional<Scope> activeEditorScope() {
    if (!PlatformUI.isWorkbenchRunning()) {
      return Optional.empty();
    }
    final AtomicReference<Scope> scope = new AtomicReference<>();
    try {
      PlatformUI.getWorkbench()
          .getDisplay()
          .syncExec(() -> activeTextEditor().flatMap(this::scopeOf).ifPresent(scope::set));
    } catch (Exception e) {
      log.warn("@exportnow: can't access editor document, falling back to source file", e);
    }
    return Optional.ofNullable(scope.get());
  }

  /** Must run on the UI thread. */
  @NotNull
  private Optional<ITextEditor> activeTextEditor() {
    return Optional.ofNullable(PlatformUI.getWorkbench().getActiveWorkbenchWindow())
        .map(IWorkbenchWindow::getActivePage)
        .map(IWorkbenchPage::getActiveEditor)
        .map(editor -> editor.getAdapter(ITextEditor.class));
  }

  @NotNull
  private Optional<Scope> scopeOf(@NotNull ITextEditor editor) {
    final IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
    if (document == null) {
      return Optional.empty();
    }
    if (editor.getSelectionProvider().getSelection() instanceof ITextSelection selection
        && selection.getLength() > 0) {
      return Optional.of(
          new Scope(document.get(), new TextRange(selection.getOffset(), selection.getLength())));
    }
    return Optional.of(wholeText(document.get()));
  }

  @NotNull
  private Scope wholeText(@NotNull String text) {
    return new Scope(text, new TextRange(0, text.length()));
  }

  @NotNull
  private String firstLine(@NotNull String text) {
    final int newline = text.indexOf('\n');
    return (newline < 0 ? text : text.substring(0, newline)).trim();
  }
}
