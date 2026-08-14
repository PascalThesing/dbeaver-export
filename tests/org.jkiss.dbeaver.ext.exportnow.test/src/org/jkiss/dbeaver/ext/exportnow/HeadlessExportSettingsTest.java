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

import com.google.gson.Gson;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.data.SQLQueryDataContainer;
import org.jkiss.dbeaver.tools.transfer.DataTransferSettings;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the transfer-settings building of {@link HeadlessExportJob}:
 * the JSON config sections must land in the right DataTransferSettings slots.
 * Runs against the real DataTransferRegistry inside the OSGi runtime.
 */
public class HeadlessExportSettingsTest {

    private static final Log log = Log.getLog(HeadlessExportSettingsTest.class);

    private HeadlessExportJob job(String json) throws DBException {
        Map<String, Object> params = JSONUtils.parseMap(new Gson(), new StringReader(json));
        return new HeadlessExportJob(
            new SQLQueryDataContainer(() -> null, new SQLQuery(null, "select 1"), null, log),
            params);
    }

    @Test
    public void unknownProcessorTypeThrows() throws Exception {
        HeadlessExportJob job = job("{\"type\": \"no-such-format\"}");
        DBException e = assertThrows(DBException.class, job::createSettings);
        assertTrue(e.getMessage().contains("no-such-format"), "error must name the unknown type");
    }

    @Test
    public void csvProcessorResolved() throws Exception {
        DataTransferSettings settings = job("{\"type\": \"csv\"}").createSettings();
        assertNotNull(settings.getProcessor(), "processor must be resolved");
        assertEquals("stream_consumer:stream.csv", settings.getProcessor().getFullId());
    }

    @Test
    public void consumerAndProducerSectionsLandInNodeSettings() throws Exception {
        DataTransferSettings settings = job(
            "{\"type\": \"csv\","
                + " \"consumer\": {\"outputFolder\": \"/tmp/exp\", \"outputFilePattern\": \"t1\"},"
                + " \"producer\": {\"fetchSize\": 5000}}").createSettings();
        DataTransferRegistry registry = DataTransferRegistry.getInstance();

        Map<String, Object> consumerSettings = settings.getNodeSettingsMap(registry.getNodeById("stream_consumer"));
        assertEquals("/tmp/exp", consumerSettings.get("outputFolder"));
        assertEquals("t1", consumerSettings.get("outputFilePattern"));

        Map<String, Object> producerSettings = settings.getNodeSettingsMap(registry.getNodeById("database_producer"));
        assertEquals(5000, ((Number) producerSettings.get("fetchSize")).intValue());
    }

    @Test
    public void missingSectionsYieldEmptySettingsNotErrors() throws Exception {
        DataTransferSettings settings = job("{\"type\": \"json\"}").createSettings();
        DataTransferRegistry registry = DataTransferRegistry.getInstance();
        assertTrue(settings.getNodeSettingsMap(registry.getNodeById("stream_consumer")).isEmpty());
        assertTrue(settings.getNodeSettingsMap(registry.getNodeById("database_producer")).isEmpty());
    }
}
