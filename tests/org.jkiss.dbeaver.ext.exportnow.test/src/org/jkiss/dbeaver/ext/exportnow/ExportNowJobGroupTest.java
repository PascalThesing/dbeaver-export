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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.eclipse.core.runtime.jobs.JobGroup;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the maxJobCount → JobGroup mapping (DBeaver's own semantics: cap on simultaneously
 * running exports of one run; absent = unlimited).
 */
public class ExportNowJobGroupTest {

  private final SQLCommandExportNow handler = new SQLCommandExportNow();

  @Test
  public void absentOrZeroMeansUnlimited() {
    assertNull(
        handler.createJobGroup(new ExportConfig(Map.of()), 10), "absent key must not throttle");
    assertNull(
        handler.createJobGroup(new ExportConfig(Map.of("maxJobCount", 0)), 10),
        "0 must not throttle");
  }

  @Test
  public void singleBlockNeedsNoGroup() {
    assertNull(handler.createJobGroup(new ExportConfig(Map.of("maxJobCount", 4)), 1));
  }

  @Test
  public void numericValueCapsParallelism() {
    // Gson parses JSON numbers as Double - must still coerce
    JobGroup group = handler.createJobGroup(new ExportConfig(Map.of("maxJobCount", 4.0d)), 10);
    assertEquals(4, group.getMaxThreads());

    group = handler.createJobGroup(new ExportConfig(Map.of("maxJobCount", "4")), 10);
    assertEquals(4, group.getMaxThreads());
  }
}
