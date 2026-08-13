/*
 * DBeaver headless export command — UI tests
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.ext.exportnow.ui;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.notifications.NotificationUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI smoke test: runs in a real workbench (tycho-surefire UI harness) with the
 * DBeaver platform booted, and pushes all three export notifications through
 * the real notification pipeline (registry lookup, popup settings, sink).
 * Guards against regressions the plain unit tests can't see: unregistered
 * notification ids throw here, and popups being disabled by default would
 * fail the enablement assertion.
 */
public class NotificationPopupUITest {

    @Test
    public void notificationPipelineAcceptsAllExportNotifications() {
        assertTrue(PlatformUI.isWorkbenchRunning(), "workbench must be running (UI harness)");
        assertNotNull(DBWorkbench.getPlatform(), "DBeaver platform must boot inside the workbench");
        assertTrue(DBWorkbench.getPlatform().getPreferenceStore()
                .getBoolean(ModelPreferences.NOTIFICATIONS_ENABLED),
            "notification popups must be enabled by default");

        // Throws IllegalArgumentException if an id is not registered via the
        // org.jkiss.dbeaver.notifications extension point
        NotificationUtils.sendNotification(
            "exportnow.ok", "Export finished", "testdb: select 1", DBPMessageType.INFORMATION, null);
        NotificationUtils.sendNotification(
            "exportnow.error", "Export failed", "testdb: select 1", DBPMessageType.ERROR, null);
        NotificationUtils.sendNotification(
            "exportnow.cancelled", "Export cancelled", "partial file may remain", DBPMessageType.INFORMATION, null);

        // Pump the event loop so the popup jobs run to completion
        final Display display = PlatformUI.getWorkbench().getDisplay();
        final long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            if (!display.readAndDispatch()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
