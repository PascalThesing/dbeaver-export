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
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Streams rows into an xlsx file with fastexcel. Knows Excel's limits: rows beyond a sheet's
 * capacity continue on a new sheet (with the header again), text longer than a cell holds is
 * truncated, and values Excel cannot store as numbers or dates (before 1900, NaN, infinity) are
 * written as text. Expects plain Java values: the DBeaver-specific conversion happens in {@link
 * XlsxExporter}.
 */
final class XlsxSheetWriter {

  static final int EXCEL_MAX_ROWS = Worksheet.MAX_ROWS;
  static final int EXCEL_MAX_CELL_LENGTH = 32_767;

  private static final int FLUSH_EVERY_ROWS = 10_000;
  private static final String DATE_FORMAT = "yyyy-mm-dd";
  private static final String DATE_TIME_FORMAT = "yyyy-mm-dd hh:mm:ss";
  private static final String TIME_FORMAT = "hh:mm:ss";
  private static final DateTimeFormatter DATE_TIME_TEXT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int EXCEL_FIRST_YEAR = 1900;
  private static final double SECONDS_PER_DAY = 86_400;

  private final Workbook workbook;
  private final String sheetName;
  @Nullable private final List<String> header;
  private final int maxRowsPerSheet;
  private Worksheet sheet;
  private int sheetCount;
  private int row;
  private int truncatedCells;

  /**
   * @param header column labels for the first row of every sheet, {@code null} for no header
   * @param maxRowsPerSheet rows per sheet including the header, at most {@link #EXCEL_MAX_ROWS}
   */
  XlsxSheetWriter(
      @NotNull OutputStream out,
      @NotNull String sheetName,
      @Nullable List<String> header,
      int maxRowsPerSheet) {
    this.workbook = new Workbook(out, "DBeaver", "1.0");
    this.sheetName = sheetName;
    this.header = header;
    this.maxRowsPerSheet = Math.min(maxRowsPerSheet, EXCEL_MAX_ROWS);
    startSheet();
  }

  void writeRow(@NotNull Object[] values) throws IOException {
    if (row >= maxRowsPerSheet) {
      sheet.finish();
      startSheet();
    }
    for (int column = 0; column < values.length; column++) {
      writeCell(column, values[column]);
    }
    row++;
    if (row % FLUSH_EVERY_ROWS == 0) {
      sheet.flush();
    }
  }

  /** Completes the file. The target stream stays open: its owner closes it. */
  void finish() throws IOException {
    workbook.finish();
  }

  /** How many cells were cut to {@link #EXCEL_MAX_CELL_LENGTH} characters. */
  int truncatedCells() {
    return truncatedCells;
  }

  private void startSheet() {
    sheetCount++;
    sheet =
        workbook.newWorksheet(sheetCount == 1 ? sheetName : sheetName + " (" + sheetCount + ")");
    row = 0;
    if (header != null) {
      for (int column = 0; column < header.size(); column++) {
        sheet.inlineString(row, column, header.get(column));
        sheet.style(row, column).bold().set();
      }
      row++;
    }
  }

  private void writeCell(int column, @Nullable Object value) {
    switch (value) {
      case null -> {}
      case Boolean bool -> sheet.value(row, column, bool);
      case Double number when !Double.isFinite(number) -> text(column, number.toString());
      case Float number when !Float.isFinite(number) -> text(column, number.toString());
      case Number number -> sheet.value(row, column, number);
      case java.sql.Date date -> date(column, date.toLocalDate());
      case java.sql.Time time -> time(column, time.toLocalTime());
      case java.sql.Timestamp timestamp -> dateTime(column, timestamp.toLocalDateTime());
      case Date date ->
          dateTime(column, date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
      case LocalDate date -> date(column, date);
      case LocalDateTime dateTime -> dateTime(column, dateTime);
      case LocalTime time -> time(column, time);
      default -> text(column, value.toString());
    }
  }

  private void date(int column, @NotNull LocalDate date) {
    if (date.getYear() < EXCEL_FIRST_YEAR) {
      text(column, date.toString());
      return;
    }
    sheet.value(row, column, date);
    sheet.style(row, column).format(DATE_FORMAT).set();
  }

  private void dateTime(int column, @NotNull LocalDateTime dateTime) {
    if (dateTime.getYear() < EXCEL_FIRST_YEAR) {
      text(column, DATE_TIME_TEXT.format(dateTime));
      return;
    }
    sheet.value(row, column, dateTime);
    sheet.style(row, column).format(DATE_TIME_FORMAT).set();
  }

  /** A time of day is the fraction of a day in Excel. */
  private void time(int column, @NotNull LocalTime time) {
    sheet.value(row, column, time.toSecondOfDay() / SECONDS_PER_DAY);
    sheet.style(row, column).format(TIME_FORMAT).set();
  }

  /** Inline strings stream with the rows; shared strings would be held in memory. */
  private void text(int column, @NotNull String text) {
    if (text.length() > EXCEL_MAX_CELL_LENGTH) {
      truncatedCells++;
      sheet.inlineString(row, column, text.substring(0, EXCEL_MAX_CELL_LENGTH));
      return;
    }
    sheet.inlineString(row, column, text);
  }
}
