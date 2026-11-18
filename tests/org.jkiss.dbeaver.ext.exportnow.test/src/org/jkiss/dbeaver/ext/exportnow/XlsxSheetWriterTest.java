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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.jkiss.dbeaver.ext.exportnow.XlsxContent.Cell;
import org.junit.jupiter.api.Test;

/** The xlsx writer behind the built-in xlsx exporter, checked on the file Excel would open. */
public class XlsxSheetWriterTest {

  private static final String SHEET = "Export";

  private XlsxContent write(List<String> header, int maxRows, Object[]... rows) throws Exception {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final XlsxSheetWriter writer = new XlsxSheetWriter(out, SHEET, header, maxRows);
    for (Object[] row : rows) {
      writer.writeRow(row);
    }
    writer.finish();
    return XlsxContent.read(out.toByteArray());
  }

  private XlsxContent write(List<String> header, Object[]... rows) throws Exception {
    return write(header, XlsxSheetWriter.EXCEL_MAX_ROWS, rows);
  }

  @Test
  public void headerRowComesFirstInBold() throws Exception {
    final XlsxContent xlsx = write(List.of("id", "name"), new Object[] {1, "a"});
    assertEquals(List.of(SHEET), xlsx.sheetNames());
    assertEquals(List.of("id", "name"), xlsx.row(SHEET, 1));
    assertTrue(xlsx.cell(SHEET, "A1").bold(), "header must be bold");
    assertEquals(List.of("1", "a"), xlsx.row(SHEET, 2));
    assertFalse(xlsx.cell(SHEET, "B2").bold(), "data must not be bold");
  }

  @Test
  public void noHeaderRowWithoutHeader() throws Exception {
    final XlsxContent xlsx = write(null, new Object[] {1, "a"});
    assertEquals(List.of("1", "a"), xlsx.row(SHEET, 1));
    assertEquals(1, xlsx.rowCount(SHEET));
  }

  @Test
  public void valuesKeepTheirTypes() throws Exception {
    final XlsxContent xlsx =
        write(
            null,
            new Object[] {
              new BigDecimal("1234.5"),
              42L,
              Boolean.TRUE,
              "Grüße <&> \"x\"",
              null,
              java.sql.Date.valueOf("2026-11-18"),
              Timestamp.valueOf("2026-11-18 14:30:05"),
              LocalDate.of(2026, 11, 18),
              LocalDateTime.of(2026, 11, 18, 14, 30, 5),
              Time.valueOf("14:30:05")
            });
    final List<Cell> cells = xlsx.cells(SHEET);
    assertEquals(new Cell("A1", "n", "1234.5", null, false), withoutFormat(cells.get(0)));
    assertEquals("42", xlsx.cell(SHEET, "B1").text());
    assertEquals("n", xlsx.cell(SHEET, "B1").type());
    assertEquals("b", xlsx.cell(SHEET, "C1").type());
    assertEquals("1", xlsx.cell(SHEET, "C1").text());
    assertEquals("Grüße <&> \"x\"", xlsx.cell(SHEET, "D1").text());
    assertTrue(
        xlsx.cell(SHEET, "E1") == null || xlsx.cell(SHEET, "E1").text().isEmpty(),
        "null must give an empty cell: " + xlsx.cell(SHEET, "E1"));
    assertDate(xlsx.cell(SHEET, "F1"), "yyyy-mm-dd");
    assertDate(xlsx.cell(SHEET, "G1"), "yyyy-mm-dd hh:mm:ss");
    assertDate(xlsx.cell(SHEET, "H1"), "yyyy-mm-dd");
    assertDate(xlsx.cell(SHEET, "I1"), "yyyy-mm-dd hh:mm:ss");
    assertDate(xlsx.cell(SHEET, "J1"), "hh:mm:ss");
    assertEquals(
        xlsx.cell(SHEET, "F1").text(),
        xlsx.cell(SHEET, "H1").text(),
        "java.sql.Date and LocalDate of the same day must give the same serial");
    assertTrue(
        Double.parseDouble(xlsx.cell(SHEET, "G1").text()) > 46000,
        "timestamp must be an Excel serial number: " + xlsx.cell(SHEET, "G1"));
  }

  @Test
  public void datesBeforeExcelsFirstDayAreText() throws Exception {
    final XlsxContent xlsx =
        write(
            null,
            new Object[] {
              LocalDate.of(1899, 12, 31),
              java.sql.Date.valueOf("1850-01-01"),
              Timestamp.valueOf("1800-01-01 10:00:00"),
              LocalDateTime.of(1899, 1, 1, 8, 0)
            });
    assertEquals(
        List.of("1899-12-31", "1850-01-01", "1800-01-01 10:00:00", "1899-01-01 08:00:00"),
        xlsx.row(SHEET, 1));
    xlsx.cells(SHEET).forEach(c -> assertEquals("inlineStr", c.type(), c.toString()));
  }

  @Test
  public void nonFiniteNumbersAreText() throws Exception {
    final XlsxContent xlsx =
        write(null, new Object[] {Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY});
    assertEquals(List.of("NaN", "Infinity", "-Infinity"), xlsx.row(SHEET, 1));
    xlsx.cells(SHEET).forEach(c -> assertEquals("inlineStr", c.type(), c.toString()));
  }

  @Test
  public void textLongerThanAnExcelCellIsTruncated() throws Exception {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final XlsxSheetWriter writer =
        new XlsxSheetWriter(out, SHEET, null, XlsxSheetWriter.EXCEL_MAX_ROWS);
    writer.writeRow(new Object[] {"x".repeat(40_000), "short"});
    writer.finish();
    final XlsxContent xlsx = XlsxContent.read(out.toByteArray());
    assertEquals(32_767, xlsx.cell(SHEET, "A1").text().length());
    assertEquals("short", xlsx.cell(SHEET, "B1").text());
    assertEquals(1, writer.truncatedCells());
  }

  @Test
  public void rowsBeyondTheLimitContinueOnNewSheetsWithHeader() throws Exception {
    final XlsxContent xlsx =
        write(
            List.of("n"),
            3,
            new Object[] {1},
            new Object[] {2},
            new Object[] {3},
            new Object[] {4},
            new Object[] {5});
    assertEquals(List.of(SHEET, SHEET + " (2)", SHEET + " (3)"), xlsx.sheetNames());
    assertEquals(3, xlsx.rowCount(SHEET));
    assertEquals(List.of("n"), xlsx.row(SHEET + " (2)", 1));
    assertEquals(List.of("3"), xlsx.row(SHEET + " (2)", 2));
    assertEquals(List.of("5"), xlsx.row(SHEET + " (3)", 2));
    assertNull(xlsx.cell(SHEET + " (3)", "A3"));
  }

  @Test
  public void charactersXmlForbidsAreDropped() throws Exception {
    final XlsxContent xlsx = write(null, new Object[] {"a\u0001b\u0000c"});
    assertEquals("abc", xlsx.cell(SHEET, "A1").text());
  }

  @Test
  public void emptyResultStillGivesAValidFile() throws Exception {
    final XlsxContent xlsx = write(List.of("id"));
    assertEquals(List.of("id"), xlsx.row(SHEET, 1));
  }

  @Test
  public void finishLeavesTheTargetStreamOpen() throws Exception {
    final boolean[] closed = {false};
    final ByteArrayOutputStream out =
        new ByteArrayOutputStream() {
          @Override
          public void close() {
            closed[0] = true;
          }
        };
    final XlsxSheetWriter writer = new XlsxSheetWriter(out, SHEET, List.of("a"), 10);
    writer.writeRow(new Object[] {1});
    writer.finish();
    assertFalse(closed[0], "DBeaver's stream consumer owns and closes the output stream");
    assertTrue(out.size() > 0);
  }

  private static void assertDate(Cell cell, String format) {
    assertEquals("n", cell.type(), "date must be a number cell: " + cell);
    assertEquals(format, cell.numberFormat(), "date format of " + cell);
  }

  private static Cell withoutFormat(Cell cell) {
    return new Cell(cell.ref(), cell.type(), cell.text(), null, cell.bold());
  }
}
