/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.spatial.ogc.wfs.transformer.handlebars;

import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.B;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.BYTES_PER_GB;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.BYTES_PER_KB;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.BYTES_PER_MB;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.BYTES_PER_PB;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.BYTES_PER_TB;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.GB;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.KB;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.MB;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.PB;
import static org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants.TB;

import ddf.catalog.data.Attribute;
import ddf.catalog.data.AttributeDescriptor;
import ddf.catalog.data.AttributeRegistry;
import ddf.catalog.data.AttributeType;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardType;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.data.types.Core;
import java.io.InputStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.xml.bind.DatatypeConverter;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Namespace;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import ogc.schema.opengis.wfs_capabilities.v_1_0_0.FeatureTypeType;
import org.apache.commons.lang.StringUtils;
import org.apache.tika.io.IOUtils;
import org.boon.json.JsonException;
import org.boon.json.JsonFactory;
import org.codice.ddf.spatial.ogc.wfs.catalog.common.WfsConstants;
import org.codice.ddf.spatial.ogc.wfs.catalog.metacardtype.registry.WfsMetacardTypeRegistry;
import org.codice.ddf.spatial.ogc.wfs.featuretransformer.FeatureTransformer;
import org.codice.ddf.spatial.ogc.wfs.featuretransformer.WfsMetadata;
import org.codice.ddf.transformer.xml.streaming.Gml3ToWkt;
import org.codice.ddf.transformer.xml.streaming.impl.Gml3ToWktImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

public class HandlebarsWfsFeatureTransformer implements FeatureTransformer<FeatureTypeType> {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(HandlebarsWfsFeatureTransformer.class);

  private static final XMLInputFactory XML_INPUT_FACTORY = XMLInputFactory.newInstance();

  private static final XMLOutputFactory XML_OUTPUT_FACTORY = XMLOutputFactory.newInstance();

  private static final XMLEventFactory XML_EVENT_FACTORY = XMLEventFactory.newInstance();

  public static final String FEATURE_MEMBER_NAME = "featureMember";

  static final String ATTRIBUTE_NAME = "attributeName";

  static final String FEATURE_NAME = "featureName";

  static final String TEMPLATE = "template";

  protected static final String UTF8_ENCODING = "UTF-8";

  private static final String METACARD_ID = "metacardId";

  private String featureType;

  private QName featureTypeQName;

  private String dataUnit;

  private MetacardType metacardType;

  private List<AttributeDescriptor> attributeDescriptors;

  private Map<String, FeatureAttributeEntry> mappingEntries;

  private Map<String, String> contextMap;

  private WfsMetacardTypeRegistry metacardTypeRegistry;

  private AttributeRegistry attributeRegistry;

  public HandlebarsWfsFeatureTransformer() {
    attributeDescriptors = new ArrayList<>();
    contextMap = new HashMap<>();
    mappingEntries = new HashMap<>();
  }

  @Override
  public Optional<Metacard> apply(InputStream inputStream, WfsMetadata metadata) {
    if (!isStateValid()) {
      LOGGER.debug("Transformer state is invalid: {}, {}", featureType, mappingEntries);
      return Optional.empty();
    }
    cleanState();

    lookupMetacardType(metadata);
    if (metacardType == null) {
      return Optional.empty();
    }

    populateContextMap(inputStream);
    if (CollectionUtils.isEmpty(contextMap)) {
      return Optional.empty();
    }

    MetacardImpl metacard = (MetacardImpl) createMetacard();

    String id = null;
    if (StringUtils.isBlank(metacard.getId())) {
      id = contextMap.get(METACARD_ID);
      if (StringUtils.isNotBlank(id)) {
        metacard.setId(id);
      } else {
        LOGGER.debug("Feature id is blank. Unable to set metacard id.");
      }
    }

    metacard.setSourceId(metacard.getId());

    Date date = new Date();
    if (metacard.getEffectiveDate() == null) {
      metacard.setEffectiveDate(date);
    }
    if (metacard.getCreatedDate() == null) {
      metacard.setCreatedDate(date);
    }
    if (metacard.getModifiedDate() == null) {
      metacard.setModifiedDate(date);
    }

    if (StringUtils.isBlank(metacard.getTitle())) {
      metacard.setTitle(id);
    }
    metacard.setContentTypeName(metacardType.getName());
    try {
      metacard.setTargetNamespace(
          new URI(WfsConstants.NAMESPACE_URN_ROOT + metacardType.getName()));
    } catch (URISyntaxException e) {
      LOGGER.debug(
          "Unable to set Target Namespace on metacard: {}.",
          WfsConstants.NAMESPACE_URN_ROOT + metacardType.getName(),
          e);
    }

    return Optional.of(metacard);
  }

  private void cleanState() {
    attributeDescriptors.clear();
    contextMap.clear();
  }

  /**
   * Reads in the FeatureMember from the inputstream, populating the contextMap with the XML tag
   * names and values
   *
   * @param inputStream the stream containing the FeatureMember xml document
   */
  private void populateContextMap(InputStream inputStream) {
    Map<String, String> namespaces = new HashMap<>();
    String gmlNamespaceAlias = null;
    String id;

    try {
      XMLEventReader xmlEventReader = getXmlEventReader(inputStream);

      while (xmlEventReader.hasNext()) {
        XMLEvent xmlEvent = xmlEventReader.nextEvent();
        if (xmlEvent.isStartElement()) {
          StartElement startElement = xmlEvent.asStartElement();
          String elementName = startElement.getName().getLocalPart();

          if (elementName.equals(FEATURE_MEMBER_NAME)) {
            mapNamespaces(startElement, namespaces);
            gmlNamespaceAlias =
                namespaces
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getValue().contains("gml"))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .get();

            while (!xmlEventReader.peek().isStartElement()) {
              xmlEventReader.nextEvent();
            }

            if (!canMap(xmlEventReader.peek().asStartElement(), namespaces)) {
              return;
            }

          } else if (elementName.equals(featureTypeQName.getLocalPart())) {

            id = getIdAttributeValue(startElement, namespaces, gmlNamespaceAlias);
            contextMap.put(METACARD_ID, id);
          } else {

            if (xmlEventReader.hasNext()) {
              XMLEvent eventPeek = xmlEventReader.peek();
              if (eventPeek.isCharacters()) {
                contextMap.put(elementName, xmlEventReader.nextEvent().asCharacters().getData());
              } else if (eventPeek.isStartElement()
                  && eventPeek.asStartElement().getName().getPrefix().equals(gmlNamespaceAlias)) {
                readGmlData(xmlEventReader, elementName, namespaces);
              }
            }
          }
        }
      }
    } catch (XMLStreamException e) {
      LOGGER.debug("Error transforming feature to metacard.", e);
    }
  }

  private String getIdAttributeValue(
      StartElement startElement, Map<String, String> namespaces, String namespaceAlias) {
    String id = null;
    startElement.getAttributeByName(new QName(namespaces.get(namespaceAlias), "id")).getValue();
    javax.xml.stream.events.Attribute idAttribute =
        startElement.getAttributeByName(new QName(namespaces.get(namespaceAlias), "id"));
    if (idAttribute != null) {
      id = idAttribute.getValue();
    }

    if (StringUtils.isBlank(id)) {
      for (Iterator i = startElement.getAttributes(); i.hasNext(); ) {
        idAttribute = (javax.xml.stream.events.Attribute) i.next();
        if (idAttribute != null && idAttribute.getName().getLocalPart().equals("id")) {
          id = idAttribute.getValue();
        }
      }
    }

    return id;
  }

  private XMLEventReader getXmlEventReader(InputStream inputStream) throws XMLStreamException {
    XMLEventReader xmlEventReader = XML_INPUT_FACTORY.createXMLEventReader(inputStream);
    xmlEventReader =
        XML_INPUT_FACTORY.createFilteredReader(
            xmlEventReader,
            event -> {
              if (event.isCharacters()) {
                return event.asCharacters().getData().trim().length() > 0;
              }

              return true;
            });
    return xmlEventReader;
  }

  private void readGmlData(
      XMLEventReader xmlEventReader, String elementName, Map<String, String> namespaces)
      throws XMLStreamException {
    int count = 0;
    XMLEventWriter eventWriter = null;
    StringWriter stringWriter = new StringWriter();
    boolean readData = true;

    try {
      while (readData) {
        XMLEvent xmlEvent = xmlEventReader.nextEvent();
        if (xmlEvent.isStartElement()) {
          if (count == 0) {
            eventWriter = XML_OUTPUT_FACTORY.createXMLEventWriter(stringWriter);
          }
          eventWriter.add(addNamespacesToStartElement(xmlEvent.asStartElement()));

          count++;
        } else if (xmlEvent.isEndElement()) {
          if (count == 0) {
            if (eventWriter != null) {
              eventWriter.flush();
            }
            readData = false;
          } else {
            eventWriter.add(xmlEvent);
          }
          count--;
        } else {
          if (eventWriter != null) {
            eventWriter.add(xmlEvent);
          }
        }
      }
    } finally {
      if (eventWriter != null) {
        eventWriter.close();
      }
      IOUtils.closeQuietly(stringWriter);
    }

    LOGGER.debug("String writer: {}", stringWriter);
    String wkt = getWktFromGeometry(stringWriter.toString());
    LOGGER.debug("String wkt value: {}", wkt);
    contextMap.put(elementName, wkt);
  }

  private void lookupMetacardType(WfsMetadata metadata) {
    Optional<MetacardType> optionalMetacardType =
        metacardTypeRegistry.lookupMetacardTypeBySimpleName(
            metadata.getId(), featureTypeQName.getLocalPart());
    if (optionalMetacardType.isPresent()) {
      metacardType = optionalMetacardType.get();
    } else {
      LOGGER.debug(
          "Error looking up metacard type for source id: '{}', and simple name: '{}'",
          metadata.getId(),
          featureTypeQName.getLocalPart());
    }
  }

  private Metacard createMetacard() {
    MetacardImpl metacard = new MetacardImpl(metacardType);

    List<Attribute> attributes =
        mappingEntries
            .values()
            .stream()
            .map(this::createAttribute)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    attributes.forEach(metacard::setAttribute);

    return metacard;
  }

  private StartElement addNamespacesToStartElement(StartElement startElement) {
    String prefix = startElement.getName().getPrefix();
    if (StringUtils.isBlank(prefix)) {
      return startElement;
    }

    if (isPrefixBound(startElement, prefix)) {
      return startElement;
    }

    return XML_EVENT_FACTORY.createStartElement(
        prefix,
        startElement.getNamespaceURI(prefix),
        startElement.getName().getLocalPart(),
        startElement.getAttributes(),
        Collections.singletonList(
                XML_EVENT_FACTORY.createNamespace(prefix, startElement.getNamespaceURI(prefix)))
            .iterator());
  }

  private Attribute createAttribute(FeatureAttributeEntry entry) {
    String value;
    if (StringUtils.isNotBlank(entry.getTemplateText())) {
      value = entry.getMappingFunction().apply(contextMap);
    } else {
      value = contextMap.get(entry.getFeatureProperty());
    }

    if (StringUtils.isBlank(value)) {
      LOGGER.debug("No value found for feature type: {}", entry.getFeatureProperty());
      return null;
    }

    Serializable attributeValue = getMetacardAttributeValue(entry.getFeatureProperty(), value);
    if (attributeValue == null) {
      LOGGER.debug(
          "No attribute value found for feature type: {}, attribute: {}",
          entry.getFeatureProperty(),
          entry.getAttributeName());
      return null;
    }

    return new AttributeImpl(entry.getAttributeName(), attributeValue);
  }

  private Serializable getMetacardAttributeValue(String featureName, String featureValue) {
    FeatureAttributeEntry entry = mappingEntries.get(featureName);
    if (entry == null) {
      LOGGER.debug(
          "Error handling feature name: {}, with value: {}. No mapping entry found for feature name",
          featureName,
          featureValue);
      return null;
    }

    AttributeDescriptor attributeDescriptor =
        metacardType.getAttributeDescriptor(entry.getAttributeName());
    if (attributeDescriptor == null) {
      LOGGER.debug(
          "AttributeDescriptor for attribute name {} not found. The mapping is being ignored.",
          entry.getAttributeName());
      return null;
    }

    Serializable attributeValue = null;

    if (StringUtils.equals(entry.getAttributeName(), Core.RESOURCE_SIZE)) {
      String bytes = convertToBytes(featureValue, getDataUnit());

      if (StringUtils.isNotBlank(bytes)) {
        attributeValue = bytes;
      }
    } else {
      attributeValue =
          getValueForAttributeFormat(
              attributeDescriptor.getType().getAttributeFormat(), featureValue);
    }

    return attributeValue;
  }

  protected Serializable getValueForAttributeFormat(
      AttributeType.AttributeFormat attributeFormat, String value) {

    Serializable serializable = null;
    switch (attributeFormat) {
      case BOOLEAN:
        serializable = Boolean.valueOf(value);
        break;
      case DOUBLE:
        serializable = Double.valueOf(value);
        break;
      case FLOAT:
        serializable = Float.valueOf(value);
        break;
      case INTEGER:
        serializable = Integer.valueOf(value);
        break;
      case LONG:
        serializable = Long.valueOf(value);
        break;
      case SHORT:
        serializable = Short.valueOf(value);
        break;
      case XML:
      case STRING:
      case GEOMETRY:
        serializable = value;
        break;
      case BINARY:
        try {
          serializable = value.getBytes(UTF8_ENCODING);
        } catch (UnsupportedEncodingException e) {
          LOGGER.debug("Error encoding the binary value into the metacard.", e);
        }
        break;
      case DATE:
        serializable = DatatypeConverter.parseDate(value).getTime();
        break;
      default:
        break;
    }
    return serializable;
  }

  private String getWktFromGeometry(String geometry) {
    String wkt = getWktFromGml3(geometry);
    if (StringUtils.isNotBlank(wkt)) {
      return wkt;
    }
    LOGGER.debug("Error converting gml to wkt using gml3 configuration. Trying gml2.", geometry);
    return getWktFromGml2(geometry);
  }

  private String getWktFromGml3(String geometry) {
    String wkt = null;
    Gml3ToWkt gml3ToWkt = new Gml3ToWktImpl(new org.geotools.gml3.GMLConfiguration());
    try {
      wkt = gml3ToWkt.convert(geometry);
    } catch (Exception e) {
      LOGGER.debug("Error converting gml to wkt using gml3 configuration. GML: {}.", geometry, e);
    }
    return wkt;
  }

  private String getWktFromGml2(String geometry) {
    String wkt = null;
    Gml3ToWkt gml3ToWkt = new Gml3ToWktImpl(new org.geotools.gml2.GMLConfiguration());
    try {
      wkt = gml3ToWkt.convert(geometry);
    } catch (Exception e) {
      LOGGER.debug("Error converting gml to wkt using gml2 configuration. GML: {}.", geometry, e);
    }
    return wkt;
  }

  private boolean isPrefixBound(StartElement startElement, String prefix) {
    for (Iterator i = startElement.getNamespaces(); i.hasNext(); ) {
      Namespace namespace = (Namespace) i.next();

      if (namespace.getPrefix().equals(prefix)) {
        return true;
      }
    }
    return false;
  }

  private boolean canMap(StartElement startElement, Map<String, String> namespaces) {
    return namespaces.keySet().contains(startElement.getName().getPrefix());
  }

  private void mapNamespaces(StartElement startElement, Map<String, String> map) {

    for (Iterator i = startElement.getNamespaces(); i.hasNext(); ) {
      Namespace namespace = (Namespace) i.next();
      map.put(namespace.getPrefix(), namespace.getNamespaceURI());
    }
  }

  private boolean canHandleNameSpace(Namespace namespace) {
    return featureType.equals(namespace.getNamespaceURI());
  }

  private boolean isStateValid() {
    if (StringUtils.isBlank(featureType)) {
      LOGGER.debug("Feature type must contain a value: {}", featureType);
      return false;
    }

    if (CollectionUtils.isEmpty(mappingEntries.values())) {
      LOGGER.debug("There are no mappings for feature type: {}", featureType);
      return false;
    }

    if (featureTypeQName == null) {
      LOGGER.debug(
          "Feature type must be formatted as '{URI}local-name'. Feature type: {}", featureType);
      return false;
    }

    if (attributeRegistry == null) {
      LOGGER.debug(
          "Must have access to the attribute registry. Can't transform the feature without it.");
      return false;
    }

    return true;
  }

  private void addAttributeMapping(String attributeName, String featureName, String templateText) {
    LOGGER.trace(
        "Adding attribute mapping from: {} to: {} using: {}",
        attributeName,
        featureName,
        templateText);
    mappingEntries.put(
        featureName, new FeatureAttributeEntry(attributeName, featureName, templateText));
  }

  public String getDataUnit() {
    return dataUnit;
  }

  public void setDataUnit(String unit) {
    LOGGER.trace("Setting data unit to: {}", unit);
    dataUnit = unit;
  }

  public String getFeatureType() {
    return this.featureType;
  }

  public void setFeatureType(String featureType) {
    LOGGER.trace("Setting feature type to: {}", featureType);
    this.featureType = featureType;
    featureTypeQName = QName.valueOf(featureType);
  }

  public void setMetacardTypeRegistry(WfsMetacardTypeRegistry metacardTypeRegistry) {
    this.metacardTypeRegistry = metacardTypeRegistry;
  }

  public void setAttributeRegistry(AttributeRegistry attributeRegistry) {
    this.attributeRegistry = attributeRegistry;
  }

  /**
   * Sets a list of attribute mappings from a list of JSON strings.
   *
   * @param attributeMappingsList - a list of JSON-formatted `FeatureAttributeEntry` objects.
   */
  public void setAttributeMappings(/*@Nullable*/ List<String> attributeMappingsList) {
    LOGGER.trace("Setting attribute mappings to: {}", attributeMappingsList);
    if (attributeMappingsList != null) {
      mappingEntries.clear();
      attributeMappingsList
          .stream()
          .filter(StringUtils::isNotEmpty)
          .map(
              string -> {
                try {
                  return JsonFactory.create().readValue(string, Map.class);
                } catch (JsonException e) {
                  LOGGER.debug("Failed to parse attribute mapping json '{}'", string, e);
                }
                return null;
              })
          .filter(Objects::nonNull)
          .filter(map -> map.get(ATTRIBUTE_NAME) instanceof String)
          .filter(map -> map.get(FEATURE_NAME) instanceof String)
          .filter(map -> map.get(TEMPLATE) instanceof String)
          .forEach(
              map ->
                  addAttributeMapping(
                      (String) map.get(ATTRIBUTE_NAME),
                      (String) map.get(FEATURE_NAME),
                      (String) map.get(TEMPLATE)));
    }
  }

  private String convertToBytes(String value, String unit) {

    BigDecimal resourceSize = new BigDecimal(value);
    resourceSize = resourceSize.setScale(1, BigDecimal.ROUND_HALF_UP);

    switch (unit) {
      case B:
        break;
      case KB:
        resourceSize = resourceSize.multiply(new BigDecimal(BYTES_PER_KB));
        break;
      case MB:
        resourceSize = resourceSize.multiply(new BigDecimal(BYTES_PER_MB));
        break;
      case GB:
        resourceSize = resourceSize.multiply(new BigDecimal(BYTES_PER_GB));
        break;
      case TB:
        resourceSize = resourceSize.multiply(new BigDecimal(BYTES_PER_TB));
        break;
      case PB:
        resourceSize = resourceSize.multiply(new BigDecimal(BYTES_PER_PB));
        break;
      default:
        break;
    }

    String resourceSizeAsString = resourceSize.toPlainString();
    LOGGER.debug("resource size in bytes: {}", resourceSizeAsString);
    return resourceSizeAsString;
  }
}
