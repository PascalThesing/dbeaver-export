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
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.exec.DBCExecutionSource;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the execution-run semantics: exports are document-driven and
 * scoped to a single run — the per-run data flag dedupes multiple @exportnow
 * commands in one script run, and nothing leaks into later executions (the
 * old editor-global pragma leak was the source of "unreliable" exports).
 */
public class ExportNowPartialExecutionTest {

    private static SQLScriptContext newContext(@Nullable SQLScriptContext parent) {
        return new SQLScriptContext(parent, () -> null, null, new StringWriter(), null);
    }

    @Test
    public void runFlagIsPerExecutionNotEditorGlobal() {
        // The dedup flag must NOT survive into a later execution's fresh child
        // context — that is exactly the scoping the old pragma got wrong.
        SQLScriptContext global = newContext(null);
        SQLScriptContext run1 = newContext(global);
        run1.setData("exportnow.executed", Boolean.TRUE);
        assertEquals(Boolean.TRUE, run1.getData("exportnow.executed"));

        SQLScriptContext run2 = newContext(global);
        assertNull(run2.getData("exportnow.executed"),
            "run-scoped data must not leak into a later execution");
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
