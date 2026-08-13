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

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the @exportnow parameter parsing (JSON + the trailing
 * semicolon that DBeaver's script parser includes in the command parameter).
 */
public class ExportNowParameterTest {

    @Test
    public void parsesJsonWithTrailingSemicolon() throws DBException {
        Map<String, Object> params = SQLCommandExportNow.parseParameters(
            "{\"type\": \"csv\", \"consumer\": {\"outputFolder\": \"/tmp/out\", \"outputFilePattern\": \"t1\"}};");
        assertEquals("csv", JSONUtils.getString(params, "type"));
        assertEquals("/tmp/out", JSONUtils.getString(JSONUtils.getObject(params, "consumer"), "outputFolder"));
        assertEquals("t1", JSONUtils.getString(JSONUtils.getObject(params, "consumer"), "outputFilePattern"));
    }

    @Test
    public void parsesJsonWithoutSemicolon() throws DBException {
        Map<String, Object> params = SQLCommandExportNow.parseParameters("{\"type\": \"json\"}");
        assertEquals("json", JSONUtils.getString(params, "type"));
    }

    @Test
    public void rejectsMissingType() {
        assertThrows(DBException.class, () ->
            SQLCommandExportNow.parseParameters("{\"consumer\": {\"outputFolder\": \"/tmp\"}}"));
    }

    @Test
    public void rejectsMalformedJson() {
        assertThrows(DBException.class, () ->
            SQLCommandExportNow.parseParameters("{\"type\": \"csv\" oops"));
    }
}
