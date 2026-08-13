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

import org.eclipse.core.runtime.IStatus;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.exec.DBCExecutionSource;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLPragmaHandler;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for partial (statement-wise) export execution: an @exportnow command
 * executed on its own arms a pragma in the editor-global script context, a
 * LATER execution (fresh child context, as SQLEditor creates per run) must see
 * it, and the pragma handler must consume only the pragma so the query itself
 * still runs and the script continues.
 */
public class ExportNowPartialExecutionTest {

    private static SQLScriptContext newContext(@Nullable SQLScriptContext parent) {
        return new SQLScriptContext(parent, () -> null, null, new StringWriter(), null);
    }

    @Test
    public void pragmaArmedInOneExecutionIsVisibleInLaterExecution() {
        // Mirrors SQLEditor: one global context per editor, a fresh child per execution
        SQLScriptContext global = newContext(null);
        SQLScriptContext execution1 = newContext(global);
        execution1.setPragma(SQLCommandExportNow.PRAGMA_EXPORT_NOW, Map.of("type", "csv"));

        SQLScriptContext execution2 = newContext(global);
        Map<String, Map<String, Object>> pragmas = execution2.getPragmas();
        assertTrue(pragmas.containsKey(SQLCommandExportNow.PRAGMA_EXPORT_NOW),
            "pragma armed in execution 1 must be visible in a later execution of the same editor");
        assertEquals("csv", pragmas.get(SQLCommandExportNow.PRAGMA_EXPORT_NOW).get("type"));
    }

    @Test
    public void pragmaHandlerConsumesOnlyThePragma() throws DBException {
        int result = new SQLPragmaExportNow().processPragma(
            new VoidProgressMonitor(),
            new StubDataContainer(),
            Map.of("type", "csv"));
        assertEquals(SQLPragmaHandler.RESULT_CONSUME_PRAGMA, result,
            "must consume only the pragma: the query still executes and the script continues");
        assertFalse((result & SQLPragmaHandler.RESULT_CONSUME_QUERY) != 0,
            "consuming the query would halt the script (the dbeaver#19143 bug)");
    }

    @Test
    public void exportJobFailsGracefullyOnUnknownProcessorType() {
        HeadlessExportJob job = new HeadlessExportJob(new StubDataContainer(), Map.of("type", "no-such-format"));
        IStatus status = job.run(new VoidProgressMonitor());
        assertNotNull(status);
        assertEquals(IStatus.ERROR, status.getSeverity(),
            "unknown processor type must yield an error status, not throw or hang");
    }

    /** Minimal container stub; the export job never gets far enough to read data. */
    private static final class StubDataContainer implements DBSDataContainer {
        @Override
        public String getName() {
            return "stub-container";
        }

        @Override
        public String getDescription() {
            return null;
        }

        @Override
        public DBSObject getParentObject() {
            return null;
        }

        @Override
        public DBPDataSource getDataSource() {
            return null;
        }

        @Override
        public boolean isPersisted() {
            return false;
        }

        @Override
        public String[] getSupportedFeatures() {
            return new String[0];
        }

        @NotNull
        @Override
        public DBCStatistics readData(
            @Nullable DBCExecutionSource source,
            @NotNull DBCSession session,
            @NotNull DBDDataReceiver dataReceiver,
            @Nullable DBDDataFilter dataFilter,
            long firstRow,
            long maxRows,
            long flags,
            int fetchSize
        ) {
            return new DBCStatistics();
        }

        @Override
        public long countData(
            @NotNull DBCExecutionSource source,
            @NotNull DBCSession session,
            @Nullable DBDDataFilter dataFilter,
            long flags
        ) {
            return 0;
        }
    }
}
