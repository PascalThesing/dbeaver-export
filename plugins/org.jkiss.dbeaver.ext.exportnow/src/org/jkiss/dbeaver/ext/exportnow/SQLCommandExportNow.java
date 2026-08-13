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
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLControlCommand;
import org.jkiss.dbeaver.model.sql.SQLControlCommandHandler;
import org.jkiss.dbeaver.model.sql.SQLControlResult;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.utils.CommonUtils;

import java.io.StringReader;
import java.util.Map;

/**
 * Handler for the {@code @exportnow} control command.
 * <p>
 * Works like the built-in {@code @export} — the JSON holds only transfer settings
 * and the query to export is the next statement in the script — but the transfer
 * runs headlessly in a background job, the query result is still shown in the
 * editor, and the script continues. Multiple exports in one script all execute,
 * each allowed to fail independently.
 */
public class SQLCommandExportNow implements SQLControlCommandHandler {

    static final String PRAGMA_EXPORT_NOW = "exportnow";

    @NotNull
    @Override
    public SQLControlResult handleCommand(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLControlCommand command,
        @NotNull SQLScriptContext scriptContext
    ) throws DBException {
        String parameter = CommonUtils.notEmpty(command.getParameter()).trim();
        while (parameter.endsWith(";")) {
            parameter = parameter.substring(0, parameter.length() - 1).trim();
        }
        final Map<String, Object> params;
        try {
            params = JSONUtils.parseMap(new Gson(), new StringReader(parameter));
        } catch (Exception e) {
            throw new DBException(
                "Invalid syntax. Use '@exportnow {\"type\": <type>, \"producer\": {...}, \"consumer\": {...}, \"processor\": {...}}'", e);
        }
        if (CommonUtils.isEmpty(JSONUtils.getString(params, HeadlessExportJob.PARAMETER_TYPE))) {
            throw new DBException("`type` attribute is mandatory for @exportnow");
        }

        scriptContext.setPragma(PRAGMA_EXPORT_NOW, params);

        return SQLControlResult.success();
    }
}
