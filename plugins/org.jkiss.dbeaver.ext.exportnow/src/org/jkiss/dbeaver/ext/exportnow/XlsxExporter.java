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

import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.data.DBDContentStorage;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite;
import org.jkiss.dbeaver.tools.transfer.stream.exporter.StreamExporterAbstract;
import org.jkiss.dbeaver.utils.ContentUtils;
import org.jkiss.utils.CommonUtils;

/**
 * The built-in xlsx export format ({@code stream.exportnow.xlsx}), used when DBeaver's optional
 * Office extension is not installed. Numbers, booleans and dates stay typed cells; every other
 * value is written as DBeaver displays it. The xlsx details live in {@link XlsxSheetWriter}.
 */
public class XlsxExporter extends StreamExporterAbstract {

  static final String PROCESSOR_ID = "stream.exportnow.xlsx";

  private static final Log log = Log.getLog(XlsxExporter.class);
  private static final String PROP_HEADER = "header";
  private static final String PROP_NULL_STRING = "nullString";
  private static final String NO_HEADER = "none";
  private static final String SHEET_NAME = "Export";

  private boolean header;
  @Nullable private String nullString;
  private DBDAttributeBinding[] columns;
  private XlsxSheetWriter writer;

  @Override
  public void init(@NotNull IStreamDataExporterSite site) throws DBException {
    super.init(site);
    final Map<String, Object> properties = site.getProperties();
    header = !NO_HEADER.equalsIgnoreCase(CommonUtils.toString(properties.get(PROP_HEADER)));
    nullString = CommonUtils.nullIfEmpty(CommonUtils.toString(properties.get(PROP_NULL_STRING)));
  }

  @Override
  public void exportHeader(DBCSession session) {
    columns = getSite().getAttributes();
    writer =
        new XlsxSheetWriter(
            getSite().getOutputStream(),
            SHEET_NAME,
            header ? labels() : null,
            XlsxSheetWriter.EXCEL_MAX_ROWS);
  }

  @Override
  public void exportRow(DBCSession session, DBCResultSet resultSet, Object[] row)
      throws DBException, IOException {
    final Object[] cells = new Object[row.length];
    for (int i = 0; i < row.length; i++) {
      cells[i] = cellValue(session, columns[i], row[i]);
    }
    writer.writeRow(cells);
  }

  @Override
  public void exportFooter(DBRProgressMonitor monitor) throws IOException {
    writer.finish();
    if (writer.truncatedCells() > 0) {
      log.warn(
          writer.truncatedCells()
              + " cell(s) cut to Excel's limit of "
              + XlsxSheetWriter.EXCEL_MAX_CELL_LENGTH
              + " characters");
    }
  }

  @NotNull
  private List<String> labels() {
    return Arrays.stream(columns)
        .map(
            column -> CommonUtils.isEmpty(column.getLabel()) ? column.getName() : column.getLabel())
        .toList();
  }

  /** Typed values pass through; everything else becomes DBeaver's display text. */
  @Nullable
  private Object cellValue(
      @NotNull DBCSession session, @NotNull DBDAttributeBinding column, @Nullable Object value)
      throws DBException, IOException {
    if (DBUtils.isNullValue(value)) {
      return nullString;
    }
    if (value instanceof DBDContent content) {
      return contentValue(session, column, content);
    }
    if (value instanceof Number
        || value instanceof Boolean
        || value instanceof Date
        || value instanceof LocalDate
        || value instanceof LocalDateTime
        || value instanceof LocalTime) {
      return value;
    }
    return getValueDisplayString(column, value);
  }

  /** LOBs: text is read (only as much as a cell holds), binary shows DBeaver's placeholder. */
  @Nullable
  private Object contentValue(
      @NotNull DBCSession session, @NotNull DBDAttributeBinding column, @NotNull DBDContent content)
      throws DBException, IOException {
    try {
      final DBDContentStorage storage = content.getContents(session.getProgressMonitor());
      if (storage == null) {
        return nullString;
      }
      if (!ContentUtils.isTextContent(content)) {
        return getValueDisplayString(column, content);
      }
      try (Reader reader = storage.getContentReader()) {
        return readUpTo(reader, XlsxSheetWriter.EXCEL_MAX_CELL_LENGTH + 1);
      }
    } finally {
      content.release();
    }
  }

  /** One character more than a cell holds, so the writer still notices the truncation. */
  @NotNull
  private static String readUpTo(@NotNull Reader reader, int maxChars) throws IOException {
    final char[] buffer = new char[maxChars];
    int length = 0;
    while (length < maxChars) {
      final int read = reader.read(buffer, length, maxChars - length);
      if (read < 0) {
        break;
      }
      length += read;
    }
    return new String(buffer, 0, length);
  }
}
