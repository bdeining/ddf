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

import static ddf.catalog.transformer.csv.common.CsvTransformer.getAllAttributes;
import static ddf.catalog.transformer.csv.common.CsvTransformer.getOnlyRequestedAttributes;
import static ddf.catalog.transformer.csv.common.CsvTransformer.sortAttributes;
import static ddf.catalog.transformer.csv.common.CsvTransformer.writeMetacardsToCsv;
import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableMap;
import ddf.catalog.Constants;
import ddf.catalog.data.AttributeDescriptor;
import ddf.catalog.data.BinaryContent;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.BinaryContentImpl;
import ddf.catalog.transform.CatalogTransformerException;
import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import org.codice.ddf.catalog.transform.MultiMetacardTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

/**
 * Implements the {@link MultiMetacardTransformer} interface to transform a single {@link Metacard}
 * instance to CSV.
 *
 * @see MetacardTransformer
 * @see Metacard
 * @see Attribute
 */
public class CsvMetacardTransformer implements MultiMetacardTransformer {

  private static final Logger LOGGER = LoggerFactory.getLogger(CsvMetacardTransformer.class);

  protected static final MimeType CSV_MIME_TYPE;

  private List<String> attributeList = new ArrayList<>();
  private Map<String, String> aliasMap = new HashMap<>();
  private Set<String> excludedAttributes = new HashSet<>();

  static {
    try {
      CSV_MIME_TYPE = new MimeType("text/csv");
    } catch (MimeTypeParseException e) {
      LOGGER.info("Failure creating MIME type", e);
      throw new ExceptionInInitializerError(e);
    }
  }

  public CsvMetacardTransformer() {}

  public void setAliasOrder(List<String> aliasOrder) {
    this.attributeList.clear();
    this.aliasMap.clear();

    for (String s : aliasOrder) {
      if (s.contains("=")) {
        final String[] split = s.split("=", 2);
        this.attributeList.add(split[0]);
        this.aliasMap.put(split[0], split[1]);
      } else {
        this.attributeList.add(s);
      }
    }
  }

  public void setExcludedAttributes(List<String> excludedAttributes) {
    this.excludedAttributes = new HashSet<>(excludedAttributes);
  }

  @Override
  public List<BinaryContent> transform(
      List<Metacard> metacards, Map<String, ? extends Serializable> arguments)
      throws CatalogTransformerException {

    if (CollectionUtils.isEmpty(metacards)) {
      throw new CatalogTransformerException("Metacard list cannot be null or empty.");
    }

    // If attributes are provided, only choose the provided attributes.
    // Otherwise, choose all and sort alphabetically. In both cases, filter excluded attributes.
    Appendable csv =
        attributeList.isEmpty()
            ? transformAllAttributes(metacards)
            : transformRequestedAttributes(metacards);

    return Collections.singletonList(getBinaryContent(csv));
  }

  // Get all attributes besides excluded, sort alphabetically
  private Appendable transformAllAttributes(List<Metacard> metacards)
      throws CatalogTransformerException {

    Set<AttributeDescriptor> attributesToInclude = getAllAttributes(metacards, excludedAttributes);

    List<String> attributeOrder =
        attributesToInclude
            .stream()
            .map(AttributeDescriptor::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .collect(toList());

    List<AttributeDescriptor> sortedAttributeDescriptors =
        sortAttributes(attributesToInclude, attributeOrder);

    return writeMetacardsToCsv(metacards, sortedAttributeDescriptors, aliasMap);
  }

  // Get requested attributes only, sort based on given order.
  private Appendable transformRequestedAttributes(List<Metacard> metacards)
      throws CatalogTransformerException {

    Set<AttributeDescriptor> attributesToInclude =
        getOnlyRequestedAttributes(metacards, new HashSet<>(attributeList), excludedAttributes);

    List<AttributeDescriptor> sortedAttributeDescriptors =
        sortAttributes(attributesToInclude, attributeList);

    return writeMetacardsToCsv(metacards, sortedAttributeDescriptors, aliasMap);
  }

  private BinaryContentImpl getBinaryContent(Appendable csvText) {
    return new BinaryContentImpl(
        new ByteArrayInputStream(csvText.toString().getBytes(StandardCharsets.UTF_8)),
        CSV_MIME_TYPE);
  }

  @Override
  public String getId() {
    return "csv";
  }

  @Override
  public Set<MimeType> getMimeTypes() {
    return Collections.singleton(CSV_MIME_TYPE);
  }

  @Override
  public Map<String, Object> getProperties() {
    return new ImmutableMap.Builder<String, Object>()
        .put(Constants.SERVICE_ID, getId())
        .put("mime-type", getMimeTypes())
        .build();
  }
}
