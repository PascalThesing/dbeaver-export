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

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLPragmaHandler;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;

import java.util.Map;

/**
 * Pragma handler invoked for the query following an {@code @exportnow} command.
 * Schedules a headless background export of that query and consumes only the
 * pragma — the query itself still executes normally (result grid, query history)
 * and the script continues with the next statement.
 */
public class SQLPragmaExportNow implements SQLPragmaHandler {

    @Override
    public int processPragma(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSDataContainer container,
        @NotNull Map<String, Object> parameters
    ) throws DBException {
        new HeadlessExportJob(container, parameters).schedule();
        return RESULT_CONSUME_PRAGMA;
    }
}
