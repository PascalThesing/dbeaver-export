/*
 * DBeaver headless export command — tests
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.ext.exportnow;

import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.junit.jupiter.api.Test;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Headless tests for EditorScope: no workbench is running here, so resolution
 * must fall back to the script's saved source file (or nothing).
 */
public class EditorScopeFallbackTest {

    private final EditorScope editorScope = new EditorScope();

    @Test
    public void fallsBackToSourceFileWithoutWorkbenchEditor() throws Exception {
        final Path sourceFile = Files.createTempFile("exportnow-scope", ".sql");
        try {
            final String script = "@exportnow {\"type\": \"csv\"};\nselect 1;\n";
            Files.writeString(sourceFile, script);
            final SQLScriptContext scriptContext =
                new SQLScriptContext(null, () -> null, sourceFile, Writer.nullWriter(), null);

            final EditorScope.Scope scope = editorScope.resolve(scriptContext, null);
            assertNotNull(scope, "source-file fallback must resolve");
            assertEquals(script, scope.text());
            assertEquals(-1, scope.selectionOffset(), "file fallback has no selection");
            assertEquals(-1, scope.selectionLength());
        } finally {
            Files.deleteIfExists(sourceFile);
        }
    }

    @Test
    public void noEditorAndNoSourceFileYieldsNull() {
        assertNull(editorScope.resolve(null, null));
        assertNull(editorScope.resolve(
            new SQLScriptContext(null, () -> null, null, Writer.nullWriter(), null), null));
    }
}
