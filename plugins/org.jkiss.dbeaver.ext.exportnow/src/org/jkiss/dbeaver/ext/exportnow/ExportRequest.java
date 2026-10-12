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

/** One export of a run: its settings and the query whose result is exported. */
record ExportRequest(@NotNull ExportConfig config, @NotNull String sql) {

  @NotNull
  ExportRequest withConfig(@NotNull ExportConfig newConfig) {
    return new ExportRequest(newConfig, sql);
  }
}
