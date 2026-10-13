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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.StringReader;
import java.util.Locale;
import java.util.Map;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.exportnow.TransferSettingsFactory.ExportSettings;
import org.jkiss.dbeaver.model.data.DBDDataFormatterProfile;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.data.SQLQueryDataContainer;
import org.jkiss.dbeaver.registry.formatter.DataFormatterRegistry;
import org.jkiss.dbeaver.tools.transfer.DataTransferSettings;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TransferSettingsFactory}: the JSON config sections must land in the right
 * DataTransferSettings slots. Runs against the real DataTransferRegistry inside the OSGi runtime.
 */
public class HeadlessExportSettingsTest {

  private static final Log log = Log.getLog(HeadlessExportSettingsTest.class);

  private final TransferSettingsFactory factory = new TransferSettingsFactory();

  private ExportSettings exportSettings(String json) throws DBException {
    Map<String, Object> params = JSONUtils.parseMap(new Gson(), new StringReader(json));
    return factory.create(
        new SQLQueryDataContainer(() -> null, new SQLQuery(null, "select 1"), null, log),
        new ExportConfig(params));
  }

  private DataTransferSettings settings(String json) throws DBException {
    return exportSettings(json).transferSettings();
  }

  private Map<String, Object> consumerSettings(DataTransferSettings settings) {
    return settings.getNodeSettingsMap(
        DataTransferRegistry.getInstance().getNodeById("stream_consumer"));
  }

  @Test
  public void unknownProcessorTypeThrows() {
    DBException e =
        assertThrows(DBException.class, () -> settings("{\"type\": \"no-such-format\"}"));
    assertTrue(e.getMessage().contains("no-such-format"), "error must name the unknown type");
  }

  @Test
  public void csvProcessorResolved() throws Exception {
    DataTransferSettings settings = settings("{\"type\": \"csv\"}");
    assertNotNull(settings.getProcessor(), "processor must be resolved");
    assertEquals("stream_consumer:stream.csv", settings.getProcessor().getFullId());
  }

  @Test
  public void consumerAndProducerSectionsLandInNodeSettings() throws Exception {
    DataTransferSettings settings =
        settings(
            "{\"type\": \"csv\","
                + " \"consumer\": {\"outputFolder\": \"/tmp/exp\", \"outputFilePattern\": \"t1\"},"
                + " \"producer\": {\"fetchSize\": 5000}}");
    DataTransferRegistry registry = DataTransferRegistry.getInstance();

    Map<String, Object> consumerSettings = consumerSettings(settings);
    assertEquals("/tmp/exp", consumerSettings.get("outputFolder"));
    assertEquals("t1", consumerSettings.get("outputFilePattern"));

    Map<String, Object> producerSettings =
        settings.getNodeSettingsMap(registry.getNodeById("database_producer"));
    assertEquals(5000, ((Number) producerSettings.get("fetchSize")).intValue());
  }

  @Test
  public void missingSectionsYieldDefaultsNotErrors() throws Exception {
    DataTransferSettings settings = settings("{\"type\": \"json\"}");
    DataTransferRegistry registry = DataTransferRegistry.getInstance();
    assertTrue(consumerSettings(settings).isEmpty());
    // absent producer section still carries the restored DBeaver default:
    // loadSettings would otherwise read the missing key as false
    assertEquals(
        Map.of("openNewConnections", true),
        settings.getNodeSettingsMap(registry.getNodeById("database_producer")));
  }

  @Test
  public void processorPropertiesConvertedAndUnknownKeysSkipped() throws Exception {
    DataTransferSettings settings =
        settings("{\"type\": \"csv\", \"processor\": {\"delimiter\": \";\", \"bogusKey\": 1}}");
    Map<String, Object> csv = settings.getProcessorProperties();
    assertEquals(";", csv.get("delimiter"));
    assertTrue(!csv.containsKey("bogusKey"), "unknown processor keys must be skipped");
  }

  @Test
  public void explicitOpenNewConnectionsWins() throws Exception {
    DataTransferSettings settings =
        settings("{\"type\": \"csv\", \"producer\": {\"openNewConnections\": false}}");
    DataTransferRegistry registry = DataTransferRegistry.getInstance();
    assertEquals(
        Boolean.FALSE,
        settings
            .getNodeSettingsMap(registry.getNodeById("database_producer"))
            .get("openNewConnections"));
  }

  @Test
  public void excelPresetSetsNumberFormatAndBom() throws Exception {
    DataTransferSettings settings = settings("{\"type\": \"csv\", \"preset\": \"excel\"}");
    assertEquals(Boolean.TRUE, settings.getProcessorProperties().get("formatNumbers"));
    Map<String, Object> consumer = consumerSettings(settings);
    assertEquals("UTF-8", consumer.get("outputEncoding"));
    assertEquals(Boolean.TRUE, consumer.get("outputEncodingBOM"));
  }

  @Test
  public void explicitSettingsWinOverPreset() throws Exception {
    DataTransferSettings settings =
        settings(
            "{\"type\": \"csv\", \"preset\": \"excel\","
                + " \"processor\": {\"delimiter\": \"\\t\", \"formatNumbers\": false},"
                + " \"consumer\": {\"outputEncodingBOM\": false}}");
    assertEquals("\t", settings.getProcessorProperties().get("delimiter"));
    assertEquals(Boolean.FALSE, settings.getProcessorProperties().get("formatNumbers"));
    assertEquals(Boolean.FALSE, consumerSettings(settings).get("outputEncodingBOM"));
  }

  @Test
  public void unknownPresetOrNonCsvPresetThrows() {
    DBException unknown =
        assertThrows(
            DBException.class, () -> settings("{\"type\": \"csv\", \"preset\": \"libreoffice\"}"));
    assertTrue(unknown.getMessage().contains("Available presets: excel"), unknown.getMessage());
    assertThrows(DBException.class, () -> settings("{\"type\": \"json\", \"preset\": \"excel\"}"));
  }

  @Test
  public void unknownFormatterProfileThrowsInsteadOfSilentFallback() {
    DBException e =
        assertThrows(
            DBException.class,
            () -> settings("{\"type\": \"csv\", \"formatterProfile\": \"no-such-profile\"}"));
    assertTrue(e.getMessage().contains("no-such-profile"), e.getMessage());
  }

  @Test
  public void excelDelimiterFollowsTheProfileAndClashesWarn() throws Exception {
    DataFormatterRegistry formatters = DataFormatterRegistry.getInstance();
    DBDDataFormatterProfile german = formatters.createCustomProfile("exportnow-test-de");
    german.setLocale(Locale.GERMANY);
    DBDDataFormatterProfile english = formatters.createCustomProfile("exportnow-test-en");
    english.setLocale(Locale.US);
    try {
      ExportSettings germanExcel =
          exportSettings(
              "{\"type\": \"csv\", \"preset\": \"excel\", \"formatterProfile\":"
                  + " \"exportnow-test-de\"}");
      assertEquals(
          "exportnow-test-de",
          consumerSettings(germanExcel.transferSettings()).get("formatterProfile"));
      assertEquals(";", germanExcel.transferSettings().getProcessorProperties().get("delimiter"));
      assertTrue(germanExcel.warnings().isEmpty(), "decimal comma with ';' is fine");

      ExportSettings englishExcel =
          exportSettings(
              "{\"type\": \"csv\", \"preset\": \"excel\", \"formatterProfile\":"
                  + " \"exportnow-test-en\"}");
      assertEquals(",", englishExcel.transferSettings().getProcessorProperties().get("delimiter"));
      assertTrue(englishExcel.warnings().isEmpty(), "decimal point with ',' is fine");

      ExportSettings clash =
          exportSettings(
              "{\"type\": \"csv\", \"preset\": \"excel\", \"formatterProfile\":"
                  + " \"exportnow-test-de\", \"processor\": {\"delimiter\": \",\"}}");
      assertTrue(clash.warnings().get(0).contains("split numbers"), clash.warnings().toString());

      ExportSettings unformatted =
          exportSettings("{\"type\": \"csv\", \"processor\": {\"delimiter\": \",\"}}");
      assertTrue(unformatted.warnings().isEmpty(), "numbers aren't formatted: no clash");
    } finally {
      formatters.deleteCustomProfile(german);
      formatters.deleteCustomProfile(english);
    }
  }
}
