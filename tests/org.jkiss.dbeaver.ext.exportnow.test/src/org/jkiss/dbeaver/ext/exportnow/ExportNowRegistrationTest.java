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

import org.jkiss.dbeaver.model.sql.registry.SQLCommandHandlerDescriptor;
import org.jkiss.dbeaver.model.sql.registry.SQLCommandsRegistry;
import org.jkiss.dbeaver.model.sql.registry.SQLPragmaHandlerDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests running inside the OSGi runtime against the real DBeaver
 * bundles: our extensions are registered and the data-transfer registry ids
 * this plugin depends on still exist in the DBeaver version under test.
 * The latter is the nightly-compatibility canary against DBeaver main.
 */
public class ExportNowRegistrationTest {

    @Test
    public void commandHandlerRegistered() throws Exception {
        SQLCommandHandlerDescriptor descriptor = SQLCommandsRegistry.getInstance().getCommandHandler("exportnow");
        assertNotNull(descriptor, "@exportnow command handler not registered");
        assertInstanceOf(SQLCommandExportNow.class, descriptor.createHandler());
    }

    @Test
    public void pragmaHandlerRegistered() throws Exception {
        SQLPragmaHandlerDescriptor descriptor = SQLCommandsRegistry.getInstance().getPragmaHandler("exportnow");
        assertNotNull(descriptor, "exportnow pragma handler not registered");
        assertInstanceOf(SQLPragmaExportNow.class, descriptor.createHandler());
    }

    @Test
    public void dataTransferRegistryIdsExist() {
        DataTransferRegistry registry = DataTransferRegistry.getInstance();
        assertNotNull(registry.getNodeById("database_producer"), "database_producer node missing");
        assertNotNull(registry.getNodeById("stream_consumer"), "stream_consumer node missing");
        assertNotNull(registry.getProcessor("stream_consumer:stream.csv"), "csv stream processor missing");
    }
}
