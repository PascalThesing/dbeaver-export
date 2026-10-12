/*
 * DBeaver headless export command — UI tests
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.ext.exportnow.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.dbeaver.ext.exportnow.EditorScope;
import org.jkiss.dbeaver.ext.exportnow.TextRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * UI test for the scope resolution the execution semantics depend on: with a real editor open in
 * the workbench, {@code resolveScope} must return the live document text, and the current text
 * selection when one exists.
 */
public class EditorScopeUITest {

  private static final String SCRIPT =
      """
      @exportnow {"type": "csv"} select * from customers;
      select 'plain';
      """;

  private Path file;
  private IEditorPart editor;

  @AfterEach
  public void cleanup() throws Exception {
    if (editor != null) {
      final IWorkbenchPage page =
          PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
      page.closeEditor(editor, false);
    }
    if (file != null) {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void wrongEditorGuardFallsBackWhenCommandNotInDocument() throws Exception {
    file = Files.createTempFile("exportnow-guard", ".sql");
    Files.writeString(file, SCRIPT);
    final IWorkbenchPage page =
        PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    editor = IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(file.toUri()));

    final EditorScope scope = new EditorScope();
    // Command parameter contained in the open document: editor scope resolves
    assertNotNull(
        scope.resolve(null, "{\"type\": \"csv\"} select * from customers;").orElse(null),
        "matching command must resolve the active editor");
    // Parameter from a DIFFERENT script (user switched tabs): editor is ignored,
    // and with no source-file fallback the scope must be null
    assertNull(
        scope.resolve(null, "{\"type\": \"xlsx\"} select * from elsewhere_entirely;").orElse(null),
        "non-matching command must not use the active editor");
  }

  @Test
  public void resolvesLiveDocumentAndSelection() throws Exception {
    file = Files.createTempFile("exportnow-ui", ".sql");
    Files.writeString(file, SCRIPT);

    final IWorkbenchPage page =
        PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    editor = IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(file.toUri()));
    final ITextEditor textEditor = editor.getAdapter(ITextEditor.class);
    assertNotNull(textEditor, "opened editor must adapt to ITextEditor");

    // No selection: scope = whole live document
    EditorScope.Scope scope = new EditorScope().resolve(null, null).orElse(null);
    assertNotNull(scope, "scope must resolve from the active editor");
    assertEquals(SCRIPT, scope.text());
    assertEquals(
        new TextRange(0, SCRIPT.length()), scope.range(), "no selection: the whole document");

    // With a selection: scope carries the exact selected range
    final int selStart = SCRIPT.indexOf("select 'plain'");
    final int selLength = "select 'plain';".length();
    textEditor.getSelectionProvider().setSelection(new TextSelection(selStart, selLength));
    scope = new EditorScope().resolve(null, null).orElse(null);
    assertNotNull(scope);
    assertEquals(SCRIPT, scope.text());
    assertEquals(selStart, scope.range().offset());
    assertEquals(selLength, scope.range().length());
  }
}
