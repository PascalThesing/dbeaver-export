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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads an xlsx file back with the JDK only (zip + DOM), so tests check what Excel would see: sheet
 * names, cell types, values, number formats and bold fonts.
 */
final class XlsxContent {

  /**
   * One cell: its xlsx type ({@code inlineStr}, {@code s}, {@code b}, {@code n}), text and style.
   */
  record Cell(String ref, String type, String text, String numberFormat, boolean bold) {}

  private final Map<String, byte[]> parts = new HashMap<>();
  private final Map<String, List<Cell>> sheets = new LinkedHashMap<>();
  private final List<String> sharedStrings = new ArrayList<>();
  private final List<String> styleFormats = new ArrayList<>();
  private final List<Boolean> styleBold = new ArrayList<>();

  static XlsxContent read(byte[] xlsx) throws Exception {
    return new XlsxContent(xlsx);
  }

  /**
   * Reads via the zip's central directory, as Excel does. ZipInputStream would reject fastexcel's
   * streamed entries, whose sizes follow the data instead of preceding it.
   */
  private XlsxContent(byte[] xlsx) throws Exception {
    final Path file = Files.createTempFile("exportnow-xlsx", ".xlsx");
    try {
      Files.write(file, xlsx);
      try (ZipFile zip = new ZipFile(file.toFile())) {
        for (ZipEntry entry : Collections.list(zip.entries())) {
          try (InputStream in = zip.getInputStream(entry)) {
            parts.put(entry.getName(), in.readAllBytes());
          }
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
    readSharedStrings();
    readStyles();
    final NodeList sheetNodes = parse("xl/workbook.xml").getElementsByTagName("sheet");
    for (int i = 0; i < sheetNodes.getLength(); i++) {
      final String name = ((Element) sheetNodes.item(i)).getAttribute("name");
      sheets.put(name, readCells("xl/worksheets/sheet" + (i + 1) + ".xml"));
    }
  }

  List<String> sheetNames() {
    return List.copyOf(sheets.keySet());
  }

  List<Cell> cells(String sheet) {
    return sheets.get(sheet);
  }

  Cell cell(String sheet, String ref) {
    return sheets.get(sheet).stream().filter(c -> c.ref().equals(ref)).findFirst().orElse(null);
  }

  /** The texts of one row (1-based), in column order. */
  List<String> row(String sheet, int row) {
    return sheets.get(sheet).stream()
        .filter(c -> c.ref().replaceAll("[A-Z]+", "").equals(String.valueOf(row)))
        .map(Cell::text)
        .toList();
  }

  int rowCount(String sheet) {
    return (int)
        sheets.get(sheet).stream().map(c -> c.ref().replaceAll("[A-Z]+", "")).distinct().count();
  }

  private List<Cell> readCells(String part) throws Exception {
    final List<Cell> cells = new ArrayList<>();
    final NodeList nodes = parse(part).getElementsByTagName("c");
    for (int i = 0; i < nodes.getLength(); i++) {
      final Element c = (Element) nodes.item(i);
      final String type = c.getAttribute("t").isEmpty() ? "n" : c.getAttribute("t");
      final int style = c.getAttribute("s").isEmpty() ? 0 : Integer.parseInt(c.getAttribute("s"));
      cells.add(
          new Cell(
              c.getAttribute("r"),
              type,
              text(c, type),
              styleFormats.isEmpty() ? null : styleFormats.get(style),
              !styleBold.isEmpty() && styleBold.get(style)));
    }
    return cells;
  }

  private String text(Element c, String type) {
    return switch (type) {
      case "inlineStr" -> c.getElementsByTagName("t").item(0).getTextContent();
      case "s" ->
          sharedStrings.get(Integer.parseInt(c.getElementsByTagName("v").item(0).getTextContent()));
      default ->
          c.getElementsByTagName("v").getLength() == 0
              ? ""
              : c.getElementsByTagName("v").item(0).getTextContent();
    };
  }

  private void readSharedStrings() throws Exception {
    if (!parts.containsKey("xl/sharedStrings.xml")) {
      return;
    }
    final NodeList items = parse("xl/sharedStrings.xml").getElementsByTagName("si");
    for (int i = 0; i < items.getLength(); i++) {
      sharedStrings.add(items.item(i).getTextContent());
    }
  }

  private void readStyles() throws Exception {
    final Document styles = parse("xl/styles.xml");
    final Map<String, String> formats = new HashMap<>();
    final NodeList numFmts = styles.getElementsByTagName("numFmt");
    for (int i = 0; i < numFmts.getLength(); i++) {
      final Element fmt = (Element) numFmts.item(i);
      formats.put(fmt.getAttribute("numFmtId"), fmt.getAttribute("formatCode"));
    }
    final List<Boolean> fontBold = new ArrayList<>();
    final NodeList fonts =
        ((Element) styles.getElementsByTagName("fonts").item(0)).getElementsByTagName("font");
    for (int i = 0; i < fonts.getLength(); i++) {
      fontBold.add(((Element) fonts.item(i)).getElementsByTagName("b").getLength() > 0);
    }
    final NodeList xfs =
        ((Element) styles.getElementsByTagName("cellXfs").item(0)).getElementsByTagName("xf");
    for (int i = 0; i < xfs.getLength(); i++) {
      final Element xf = (Element) xfs.item(i);
      styleFormats.add(formats.get(xf.getAttribute("numFmtId")));
      final String fontId = xf.getAttribute("fontId");
      styleBold.add(!fontId.isEmpty() && fontBold.get(Integer.parseInt(fontId)));
    }
  }

  private Document parse(String part) throws Exception {
    final byte[] bytes = parts.get(part);
    if (bytes == null) {
      throw new IOException("missing part " + part + ", have " + parts.keySet());
    }
    return DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(bytes));
  }
}
