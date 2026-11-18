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

import static java.util.Map.entry;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.app.DBPDataFormatterRegistry;
import org.jkiss.dbeaver.model.data.DBDDataFormatterProfile;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.impl.PropertyDescriptor;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.model.sql.data.SQLQueryDataContainer;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.tools.transfer.DTConstants;
import org.jkiss.dbeaver.tools.transfer.DataTransferSettings;
import org.jkiss.dbeaver.tools.transfer.DataTransferState;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseTransferProducer;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferNodeDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferProcessorDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.jkiss.dbeaver.tools.transfer.stream.StreamTransferConsumer;
import org.jkiss.utils.CommonUtils;

/**
 * Builds DBeaver's {@link DataTransferSettings} for one headless export from the {@code
 * @exportnow} JSON — the same way DBeaver's own {@code @export} command (SQLPragmaExport) does,
 * plus the Excel preset and the data-format profile.
 */
final class TransferSettingsFactory {

  private static final Log log = Log.getLog(TransferSettingsFactory.class);

  private static final String FORMATTER_PROFILE = "formatterProfile";
  private static final String PRODUCER_NODE_ID = "database_producer";
  private static final String CONSUMER_NODE_ID = "stream_consumer";
  private static final String PROCESSOR_ID_PREFIX = CONSUMER_NODE_ID + ":stream.";
  private static final String XLSX = "xlsx";
  private static final String XLS = "xls";

  /** How to install DBeaver's Office extension, for messages. */
  static final String INSTALL_OFFICE =
      "Help > Install New Software, Work with: https://dbeaver.io/update/office/latest/,"
          + " select DBeaver Office Support, Finish, then restart DBeaver.";

  /** DBeaver's transfer settings plus warnings about values Excel would misread. */
  record ExportSettings(
      @NotNull DataTransferSettings transferSettings, @NotNull List<String> warnings) {}

  /** The database producer, the stream consumer and its processor (the export format). */
  private record Nodes(
      @NotNull DataTransferNodeDescriptor producer,
      @NotNull DataTransferNodeDescriptor consumer,
      @NotNull DataTransferProcessorDescriptor processor) {}

  /** Everything in the JSON that can be wrong, resolved once: format, preset, profile. */
  private record Checked(
      @NotNull Nodes nodes,
      @NotNull Optional<ExcelCsvPreset> preset,
      @NotNull Map<String, Object> consumerSettings,
      @NotNull DBDDataFormatterProfile profile) {}

  private final ExcelCsvPreset excelPreset = new ExcelCsvPreset();

  /**
   * Checks an export's settings without running it, the same way {@link #create} does: unknown
   * format, preset or data-format profile fail here, before any export of the run starts.
   */
  void validate(@NotNull ExportConfig config) throws DBException {
    check(config);
  }

  @NotNull
  ExportSettings create(@NotNull DBSDataContainer container, @NotNull ExportConfig config)
      throws DBException {
    final Checked checked = check(config);
    log.info("@exportnow: format " + config.type() + " via " + checked.nodes().processor().getId());
    final Map<String, Object> processorSettings =
        createProcessorSettings(
            checked.nodes().processor(), config, checked.preset(), checked.profile());
    final Map<String, Object> nodeSettings =
        nodeSettings(
            checked.nodes(),
            createProducerSettings(config),
            checked.consumerSettings(),
            processorSettings);
    return new ExportSettings(
        newTransferSettings(container, nodeSettings),
        excelPreset.splitColumnsWarning(processorSettings, checked.profile()).stream().toList());
  }

  @NotNull
  private Checked check(@NotNull ExportConfig config) throws DBException {
    final Nodes nodes = findNodes(config.type());
    final Optional<ExcelCsvPreset> preset = preset(config);
    final Map<String, Object> consumerSettings = createConsumerSettings(config, preset);
    return new Checked(nodes, preset, consumerSettings, formatterProfile(consumerSettings));
  }

  @NotNull
  private Nodes findNodes(@Nullable String type) throws DBException {
    final DataTransferRegistry registry = DataTransferRegistry.getInstance();
    final DataTransferNodeDescriptor consumer = registry.getNodeById(CONSUMER_NODE_ID);
    return new Nodes(
        registry.getNodeById(PRODUCER_NODE_ID), consumer, findProcessor(registry, consumer, type));
  }

  @NotNull
  private DataTransferProcessorDescriptor findProcessor(
      @NotNull DataTransferRegistry registry,
      @NotNull DataTransferNodeDescriptor consumerNode,
      @Nullable String type)
      throws DBException {
    final DataTransferProcessorDescriptor processor =
        registry.getProcessor(PROCESSOR_ID_PREFIX + type);
    final DataTransferProcessorDescriptor builtInXlsx =
        registry.getProcessor(CONSUMER_NODE_ID + ":" + XlsxExporter.PROCESSOR_ID);
    if (XLSX.equals(type) && (processor != null || builtInXlsx != null)) {
      // DBeaver's Office extension has more options: prefer it when installed
      return processor != null ? processor : builtInXlsx;
    }
    if (processor != null) {
      return processor;
    }
    final List<String> available =
        // every @exportnow export reads from a query container
        consumerNode.getAvailableProcessors(SQLQueryDataContainer.class).stream()
            .map(descriptor -> descriptor == builtInXlsx ? XLSX : descriptor.getId())
            .map(id -> id.replaceFirst("^stream\\.", ""))
            .distinct()
            .sorted()
            .toList();
    throw new DBException(unknownTypeMessage(type, available));
  }

  /**
   * The error for an unknown export format. The old Excel format {@code xls} exists only in
   * DBeaver's Office extension, so it says how to install it - and that {@code xlsx} needs nothing.
   */
  @NotNull
  static String unknownTypeMessage(@Nullable String type, @NotNull List<String> available) {
    final String unknown = "Can't find processor of type '" + type + "'. ";
    final String types = "Available types: " + String.join(", ", available);
    if (!XLS.equals(type)) {
      return unknown + types;
    }
    return unknown
        + "The old Excel format xls needs DBeaver's Office extension: "
        + INSTALL_OFFICE
        + " Or use \"type\": \"xlsx\", which works without it. "
        + types;
  }

  /** The node and processor settings keyed the way {@link DataTransferSettings} reads them. */
  @NotNull
  private Map<String, Object> nodeSettings(
      @NotNull Nodes nodes,
      @NotNull Map<String, Object> producerSettings,
      @NotNull Map<String, Object> consumerSettings,
      @NotNull Map<String, Object> processorSettings) {
    return Map.ofEntries(
        entry(DTConstants.PROP_PRODUCER_TYPE, nodes.producer().getId()),
        entry(DTConstants.PROP_CONSUMER_TYPE, nodes.consumer().getId()),
        entry(DTConstants.PROP_PROCESSOR_TYPE, nodes.processor().getId()),
        entry(
            DTConstants.PROP_PROCESSORS_LIST,
            Map.of(nodes.processor().getFullId(), processorSettings)),
        entry(nodes.producer().getNodeClass().getSimpleName(), producerSettings),
        entry(nodes.consumer().getNodeClass().getSimpleName(), consumerSettings));
  }

  @NotNull
  private Optional<ExcelCsvPreset> preset(@NotNull ExportConfig config) throws DBException {
    if (config.preset().isEmpty()) {
      return Optional.empty();
    }
    final String name = config.preset().get();
    if (!ExcelCsvPreset.NAME.equals(name)) {
      throw new DBException(
          "Unknown preset '" + name + "'. Available presets: " + ExcelCsvPreset.NAME);
    }
    if (!"csv".equals(config.type())) {
      throw new DBException("Preset '" + name + "' requires \"type\": \"csv\"");
    }
    return Optional.of(excelPreset);
  }

  /**
   * Producer settings with DBeaver's own default restored: absent boolean keys are read as {@code
   * false} by DatabaseProducerSettings.loadSettings, which would silently turn off {@code
   * openNewConnections} (class default {@code true}) and make all concurrent exports share a single
   * JDBC connection. An explicit user value always wins.
   */
  @NotNull
  private Map<String, Object> createProducerSettings(@NotNull ExportConfig config) {
    final Map<String, Object> producer = new HashMap<>(config.producer());
    producer.putIfAbsent("openNewConnections", true);
    return producer;
  }

  /** The user's consumer settings over the preset defaults, plus the top-level profile name. */
  @NotNull
  private Map<String, Object> createConsumerSettings(
      @NotNull ExportConfig config, @NotNull Optional<ExcelCsvPreset> preset) {
    final Map<String, Object> consumer =
        withDefaults(
            config.consumer(), preset.map(ExcelCsvPreset::consumerDefaults).orElse(Map.of()));
    config.formatterProfile().ifPresent(name -> consumer.putIfAbsent(FORMATTER_PROFILE, name));
    return consumer;
  }

  /** Every processor property with its default, overridden by the converted user values. */
  @NotNull
  private Map<String, Object> createProcessorSettings(
      @NotNull DataTransferProcessorDescriptor processor,
      @NotNull ExportConfig config,
      @NotNull Optional<ExcelCsvPreset> preset,
      @NotNull DBDDataFormatterProfile profile) {
    final Map<String, Object> values =
        withDefaults(
            config.processor(),
            preset.map(excel -> excel.processorDefaults(profile)).orElse(Map.of()));
    final Map<String, Object> properties = new HashMap<>();
    for (DBPPropertyDescriptor property : processor.getProperties()) {
      properties.put(property.getId(), property.getDefaultValue());
    }
    values.entrySet().stream()
        .filter(entry -> isKnownProperty(processor, entry.getKey()))
        .forEach(entry -> properties.put(entry.getKey(), convert(processor, entry)));
    properties.put(DTConstants.PROP_NAME, propertyNames(processor));
    return properties;
  }

  /** DBeaver lists the processor's property ids in the settings, comma-separated. */
  @NotNull
  private String propertyNames(@NotNull DataTransferProcessorDescriptor processor) {
    return Arrays.stream(processor.getProperties())
        .map(DBPPropertyDescriptor::getId)
        .collect(Collectors.joining(","));
  }

  private boolean isKnownProperty(
      @NotNull DataTransferProcessorDescriptor processor, @NotNull String name) {
    if (processor.getProperty(name) != null) {
      return true;
    }
    log.debug("Skipping unknown property " + name);
    return false;
  }

  private Object convert(
      @NotNull DataTransferProcessorDescriptor processor,
      @NotNull Map.Entry<String, Object> entry) {
    return PropertyDescriptor.convertString(
        CommonUtils.toString(entry.getValue()),
        processor.getProperty(entry.getKey()).getDataType());
  }

  /** A mutable copy of the user's values over the defaults: explicit settings always win. */
  @NotNull
  private Map<String, Object> withDefaults(
      @NotNull Map<String, Object> values, @NotNull Map<String, Object> defaults) {
    final Map<String, Object> merged = new HashMap<>(defaults);
    merged.putAll(values);
    return merged;
  }

  /**
   * The data-format profile the export uses. DBeaver silently falls back to the global profile for
   * an unknown name, so a typo is reported here instead.
   */
  @NotNull
  private DBDDataFormatterProfile formatterProfile(@NotNull Map<String, Object> consumerSettings)
      throws DBException {
    final DBPDataFormatterRegistry registry = DBWorkbench.getPlatform().getDataFormatterRegistry();
    final String name = JSONUtils.getString(consumerSettings, FORMATTER_PROFILE);
    if (CommonUtils.isEmpty(name)) {
      return registry.getGlobalProfile();
    }
    final DBDDataFormatterProfile profile = registry.getCustomProfile(name);
    if (profile == null) {
      throw new DBException(
          "Unknown formatterProfile '" + name + "'. Available profiles: " + profileNames(registry));
    }
    return profile;
  }

  @NotNull
  private String profileNames(@NotNull DBPDataFormatterRegistry registry) {
    final String names =
        registry.getCustomProfiles().stream()
            .map(DBDDataFormatterProfile::getProfileName)
            .sorted()
            .collect(Collectors.joining(", "));
    return names.isEmpty()
        ? "(none - create one in Preferences > Editors > Data Editor > Data Formats)"
        : names;
  }

  @NotNull
  private DataTransferSettings newTransferSettings(
      @NotNull DBSDataContainer container, @NotNull Map<String, Object> nodeSettings) {
    return new DataTransferSettings(
        Collections.singleton(new DatabaseTransferProducer(container, null)),
        Collections.singleton(new StreamTransferConsumer()),
        nodeSettings,
        new DataTransferState(),
        true, // selectDefaultNodes
        true, // isExport
        false, // isExitingTask
        false // isTaskRunning
        );
  }
}
