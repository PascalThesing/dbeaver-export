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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.impl.PropertyDescriptor;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.model.task.DBTTaskExecutionListener;
import org.jkiss.dbeaver.runtime.DBeaverNotifications;
import org.jkiss.dbeaver.tools.transfer.DTConstants;
import org.jkiss.dbeaver.tools.transfer.DataTransferSettings;
import org.jkiss.dbeaver.tools.transfer.DataTransferState;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseTransferProducer;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferNodeDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferProcessorDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.jkiss.dbeaver.tools.transfer.stream.StreamTransferConsumer;
import org.jkiss.dbeaver.tools.transfer.task.DTTaskHandlerTransfer;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Runs one headless data transfer for an {@code @exportnow} statement.
 * Mirrors the settings handling of DBeaver's own SQLPragmaExport, but executes the
 * transfer via {@link DTTaskHandlerTransfer} instead of opening the wizard.
 */
class HeadlessExportJob extends AbstractJob {
    private static final Log log = Log.getLog(HeadlessExportJob.class);

    static final String PARAMETER_TYPE = "type";

    private static final String PRODUCER_NODE_ID = "database_producer";
    private static final String CONSUMER_NODE_ID = "stream_consumer";
    private static final String PROCESSOR_ID_PREFIX = CONSUMER_NODE_ID + ":stream.";

    private final DBSDataContainer container;
    private final Map<String, Object> parameters;

    HeadlessExportJob(@NotNull DBSDataContainer container, @NotNull Map<String, Object> parameters) {
        super("Export data: " + container.getName());
        this.container = container;
        this.parameters = parameters;
        setUser(true);
    }

    @Override
    protected IStatus run(DBRProgressMonitor monitor) {
        final DBPDataSource dataSource = container.getDataSource();
        try {
            final DataTransferSettings settings = createSettings();
            settings.loadNodeSettings(monitor);
            new DTTaskHandlerTransfer().executeWithSettings(
                (fork, cancelable, runnable) -> runnable.run(monitor),
                null,
                Locale.getDefault(),
                log,
                null,
                NOOP_LISTENER,
                settings);
            if (dataSource != null) {
                DBeaverNotifications.showNotification(
                    dataSource,
                    "exportnow.ok",
                    "Export finished: " + container.getName(),
                    DBPMessageType.INFORMATION);
            }
            return Status.OK_STATUS;
        } catch (Throwable e) {
            log.error("Export failed: " + container.getName(), e);
            if (dataSource != null) {
                DBeaverNotifications.showNotification(
                    dataSource,
                    "exportnow.error",
                    "Export failed: " + container.getName() + "\n" + e.getMessage(),
                    DBPMessageType.ERROR);
            }
            return GeneralUtils.makeExceptionStatus("Export failed: " + container.getName(), e);
        }
    }

    @NotNull
    private DataTransferSettings createSettings() throws DBException {
        final String type = JSONUtils.getString(parameters, PARAMETER_TYPE);
        final DataTransferRegistry registry = DataTransferRegistry.getInstance();
        final DataTransferNodeDescriptor producerNode = registry.getNodeById(PRODUCER_NODE_ID);
        final DataTransferNodeDescriptor consumerNode = registry.getNodeById(CONSUMER_NODE_ID);
        final DataTransferProcessorDescriptor processor = registry.getProcessor(PROCESSOR_ID_PREFIX + type);
        if (processor == null) {
            throw new DBException("Can't find processor of type '" + type + "'");
        }

        return new DataTransferSettings(
            Collections.singleton(new DatabaseTransferProducer(container, null)),
            Collections.singleton(new StreamTransferConsumer()),
            Map.of(
                DTConstants.PROP_PRODUCER_TYPE, producerNode.getId(),
                DTConstants.PROP_CONSUMER_TYPE, consumerNode.getId(),
                DTConstants.PROP_PROCESSOR_TYPE, processor.getId(),
                DTConstants.PROP_PROCESSORS_LIST, Map.of(
                    processor.getFullId(), createProcessorSettings(processor, parameters)
                ),
                producerNode.getNodeClass().getSimpleName(), JSONUtils.getObject(parameters, DTConstants.PROP_PRODUCER_TYPE),
                consumerNode.getNodeClass().getSimpleName(), JSONUtils.getObject(parameters, DTConstants.PROP_CONSUMER_TYPE)
            ),
            new DataTransferState(),
            true,
            true,
            false,
            false
        );
    }

    @NotNull
    private static Map<String, Object> createProcessorSettings(
        @NotNull DataTransferProcessorDescriptor processor,
        @NotNull Map<String, Object> parameters
    ) {
        final Map<String, Object> properties = new HashMap<>();
        final StringJoiner names = new StringJoiner(",");
        for (DBPPropertyDescriptor property : processor.getProperties()) {
            properties.put(property.getId(), property.getDefaultValue());
            names.add(property.getId());
        }
        for (Map.Entry<String, Object> property : JSONUtils.getObject(parameters, DTConstants.PROP_PROCESSOR_TYPE).entrySet()) {
            final DBPPropertyDescriptor descriptor = processor.getProperty(property.getKey());
            if (descriptor == null) {
                log.debug("Skipping unknown property " + property.getKey());
                continue;
            }
            properties.put(property.getKey(),
                PropertyDescriptor.convertString(CommonUtils.toString(property.getValue()), descriptor.getDataType()));
        }
        properties.put(DTConstants.PROP_NAME, names.toString());
        return properties;
    }

    private static final DBTTaskExecutionListener NOOP_LISTENER = new DBTTaskExecutionListener() {
        @Override
        public void taskStarted(DBTTask task) {
        }

        @Override
        public void taskFinished(DBTTask task, Object result, Throwable error, Object settings) {
        }

        @Override
        public void subTaskFinished(DBTTask task, Throwable error, Object settings) {
        }
    };
}
