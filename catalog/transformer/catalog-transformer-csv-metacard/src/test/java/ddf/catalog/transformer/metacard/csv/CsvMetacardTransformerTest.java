/*
 * Copyright (c) Codice Foundation
 * <p/>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p/>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package ddf.catalog.transformer.metacard.csv;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ddf.catalog.data.Attribute;
import ddf.catalog.data.AttributeDescriptor;
import ddf.catalog.data.AttributeType;
import ddf.catalog.data.BinaryContent;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardType;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.impl.BasicTypes;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.data.impl.MetacardTypeImpl;
import ddf.catalog.transform.CatalogTransformerException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import javax.activation.MimeType;
import org.junit.Before;
import org.junit.Test;

public class CsvMetacardTransformerTest {

  private CsvMetacardTransformer csvMetacardTransformer;

  private static final int METACARD_COUNT = 10;

  private static final Map<String, Attribute> METACARD_DATA_MAP = new HashMap<>();

  private static final List<AttributeDescriptor> ATTRIBUTE_DESCRIPTOR_LIST = new ArrayList<>();

  private static final List<Metacard> METACARD_LIST = new ArrayList<>();

  private static final String CSV_REGEX = "[\\n\\r,]";

  @Before
  public void setUp() {
    this.csvMetacardTransformer = new CsvMetacardTransformer();

    ATTRIBUTE_DESCRIPTOR_LIST.clear();
    METACARD_DATA_MAP.clear();

    buildMetacardDataMap();
    buildMetacardList();
  }

  private static final Object[][] ATTRIBUTE_DATA = {
    {"attribute1", "value1", BasicTypes.STRING_TYPE},
    {"attribute2", "value2", BasicTypes.STRING_TYPE},
    {"attribute3", 101, BasicTypes.INTEGER_TYPE},
    {"attribute4", 3.14159, BasicTypes.DOUBLE_TYPE},
    {"attribute5", "value,5", BasicTypes.STRING_TYPE},
    {"attribute6", "value6", BasicTypes.STRING_TYPE},
    {"attribute7", "OBJECT_VALUE", BasicTypes.OBJECT_TYPE},
    {"attribute8", "BINARY_VALUE", BasicTypes.BINARY_TYPE}
  };

  @Test
  public void testTransformWithAliasConfigProvided() throws CatalogTransformerException {
    csvMetacardTransformer.setAliasOrder(
        asList(
            "attribute2=column2",
            "attribute1=column1",
            "attribute3",
            "attribute5",
            "attribute6=column6"));

    csvMetacardTransformer.setExcludedAttributes(asList("attribute4", "attribute6"));

    List<BinaryContent> content = csvMetacardTransformer.transform(METACARD_LIST, null);
    assertThat(content, hasSize(1));
    BinaryContent bc = content.get(0);

    assertThat(content, hasSize(1));
    assertThat(bc.getMimeTypeValue(), is(CsvMetacardTransformer.CSV_MIME_TYPE.getBaseType()));

    Scanner scanner = new Scanner(bc.getInputStream());
    scanner.useDelimiter(CSV_REGEX);

    String[] expectedHeaders = {"column2", "column1", "attribute3", "attribute5"};
    String[] expectedValues = {"", "value2", "value1", "101", "\"value", "5\""};

    validateCsv(scanner, expectedHeaders, expectedValues);
  }

  @Test
  public void testTransformWithExcludedOnlyConfigProvided() throws CatalogTransformerException {
    csvMetacardTransformer.setExcludedAttributes(asList("attribute1", "attribute2"));

    List<BinaryContent> content = csvMetacardTransformer.transform(METACARD_LIST, null);
    assertThat(content, hasSize(1));
    BinaryContent bc = content.get(0);

    assertThat(content, hasSize(1));
    assertThat(bc.getMimeTypeValue(), is(CsvMetacardTransformer.CSV_MIME_TYPE.getBaseType()));

    Scanner scanner = new Scanner(bc.getInputStream());
    scanner.useDelimiter(CSV_REGEX);

    String[] expectedHeaders = {"attribute3", "attribute4", "attribute5", "attribute6"};
    String[] expectedValues = {"", "101", "3.14159", "\"value", "5\"", "value6"};

    validateCsv(scanner, expectedHeaders, expectedValues);
  }

  @Test
  public void testTransformWithoutConfig() throws CatalogTransformerException {
    List<BinaryContent> content = csvMetacardTransformer.transform(METACARD_LIST, null);
    assertThat(content, hasSize(1));
    BinaryContent bc = content.get(0);

    assertThat(content, hasSize(1));
    assertThat(bc.getMimeTypeValue(), is(CsvMetacardTransformer.CSV_MIME_TYPE.getBaseType()));

    Scanner scanner = new Scanner(bc.getInputStream());
    scanner.useDelimiter(CSV_REGEX);

    // Only attributes in "attributes" config field will exist in output.
    // OBJECT types, BINARY types and excluded attributes will be filtered out
    // excluded attribute take precedence over attributes that appear in requested attribute list.
    String[] expectedHeaders = {
      "attribute1", "attribute2", "attribute3", "attribute4", "attribute5", "attribute6"
    };

    // The scanner will split "value,5" into two tokens even though the CSVPrinter will
    // handle it correctly.
    String[] expectedValues = {
      "", "value1", "value2", "101", "3.14159", "\"value", "5\"", "value6"
    };

    validateCsv(scanner, expectedHeaders, expectedValues);
  }

  @Test(expected = CatalogTransformerException.class)
  public void testTransformNullMetacardList() throws CatalogTransformerException {
    new CsvMetacardTransformer().transform(null, null);
  }

  // Verifies no exception is thrown if a single null metacard is given. CSV file is still
  // generated.
  @Test
  public void testTransformNullMetacardInList() throws CatalogTransformerException {
    List<BinaryContent> bc =
        new CsvMetacardTransformer().transform(Collections.singletonList(null), null);

    assertThat(bc.size(), is(1));
  }

  @Test
  public void testGetMimeTypes() {
    assertThat(csvMetacardTransformer.getMimeTypes(), hasSize(1));
    final MimeType mimeType = csvMetacardTransformer.getMimeTypes().stream().findFirst().get();
    assertThat(mimeType.getBaseType(), is("text/csv"));
  }

  private void buildMetacardList() {
    METACARD_LIST.clear();
    for (int i = 0; i < METACARD_COUNT; i++) {
      METACARD_LIST.add(buildMetacard());
    }
  }

  private Metacard buildMetacard() {
    MetacardType metacardType = new MetacardTypeImpl("", new HashSet<>(ATTRIBUTE_DESCRIPTOR_LIST));
    Metacard metacard = new MetacardImpl(metacardType);
    for (Attribute a : METACARD_DATA_MAP.values()) {
      metacard.setAttribute(a);
    }
    return metacard;
  }

  private void buildMetacardDataMap() {
    for (Object[] entry : ATTRIBUTE_DATA) {
      String attributeName = entry[0].toString();
      AttributeType attributeType = (AttributeType) entry[2];
      Serializable attributeValue = (Serializable) entry[1];
      Attribute attribute = new AttributeImpl(attributeName, attributeValue);
      METACARD_DATA_MAP.put(attributeName, attribute);
      ATTRIBUTE_DESCRIPTOR_LIST.add(buildAttributeDescriptor(attributeName, attributeType));
    }
  }

  private AttributeDescriptor buildAttributeDescriptor(String name, AttributeType type) {
    AttributeDescriptor attributeDescriptor = mock(AttributeDescriptor.class);
    when(attributeDescriptor.getName()).thenReturn(name);
    when(attributeDescriptor.getType()).thenReturn(type);
    return attributeDescriptor;
  }

  private void validateRecord(Scanner scanner, String[] expectedValues) {
    for (String expectedValue : expectedValues) {
      assertThat(scanner.hasNext(), is(true));
      assertThat(scanner.next(), is(expectedValue));
    }
  }

  private void validateCsv(Scanner scanner, String[] expectedHeaders, String[] expectedValues) {
    validateRecord(scanner, expectedHeaders);

    for (int i = 0; i < METACARD_COUNT; i++) {
      validateRecord(scanner, expectedValues);
    }

    // final new line causes an extra "" value at end of file
    assertThat(scanner.hasNext(), is(true));
    assertThat(scanner.next(), is(""));
    assertThat(scanner.hasNext(), is(false));
  }
}
