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
package org.codice.ddf.spatial.kml.transformer;

import static java.util.Collections.singletonList;
import static org.codice.ddf.spatial.kml.transformer.KMLTransformerImpl.encloseKml;
import static org.codice.ddf.spatial.kml.transformer.KMLTransformerImpl.getKmlGeoFromWkt;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import ddf.catalog.Constants;
import ddf.catalog.data.Attribute;
import ddf.catalog.data.BinaryContent;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.BinaryContentImpl;
import ddf.catalog.transform.CatalogTransformerException;
import de.micromata.opengis.kml.v_2_2_0.Data;
import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.ExtendedData;
import de.micromata.opengis.kml.v_2_2_0.Feature;
import de.micromata.opengis.kml.v_2_2_0.Geometry;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.KmlFactory;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import org.apache.commons.collections4.CollectionUtils;
import org.codice.ddf.catalog.transform.MultiMetacardTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KmlMultiMetacardTransformer implements MultiMetacardTransformer {

  private static final String SKIP_UNTRANSFORMABLE_ITEMS_ARG = "skipUntransformableItems";

  private static final Logger LOGGER = LoggerFactory.getLogger(KmlMultiMetacardTransformer.class);

  static final String DOC_NAME_ARG_KEY = "docName";

  @VisibleForTesting static final String KML_DOC_NAME_DEFAULT = "KML List Export";

  @VisibleForTesting static final MimeType KML_MIME_TYPE;

  @VisibleForTesting ImmutableMap<String, String> aliasMap;

  private final KmlMarshaller kmlMarshaller;

  private Set<MimeType> mimeTypes;
  private final String id;

  static {
    try {
      KML_MIME_TYPE = new MimeType("application/vnd.google-earth.kml+xml");
    } catch (MimeTypeParseException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public KmlMultiMetacardTransformer(String id, Set<MimeType> mimeTypes) {
    this.id = id;
    this.mimeTypes = mimeTypes;
    this.kmlMarshaller = new KmlMarshaller();
  }

  @Override
  public List<BinaryContent> transform(
      List<Metacard> metacards, Map<String, ? extends Serializable> arguments)
      throws CatalogTransformerException {

    if (CollectionUtils.isEmpty(metacards)) {
      throw new CatalogTransformerException("Metacard list cannot be null or empty.");
    }

    final boolean skipUntransformableItems =
        getArgumentAsBoolean(arguments, SKIP_UNTRANSFORMABLE_ITEMS_ARG);

    final Document kmlDoc = getKmlDocumentFromMetacards(metacards, skipUntransformableItems);

    final String docId = UUID.randomUUID().toString();
    final String documentName = getDocName(arguments);
    final Kml kmlResult = encloseKml(kmlDoc, docId, documentName);

    final String transformedKml = kmlMarshaller.marshal(kmlResult);

    final InputStream kmlInputStream =
        new ByteArrayInputStream(transformedKml.getBytes(StandardCharsets.UTF_8));

    return singletonList(new BinaryContentImpl(kmlInputStream, KML_MIME_TYPE));
  }

  private Document getKmlDocumentFromMetacards(
      List<Metacard> metacards, boolean skipUntransformableItems)
      throws CatalogTransformerException {
    final Document kmlDoc = KmlFactory.createDocument();
    final List<Feature> placemarks = kmlDoc.getFeature();
    for (Metacard metacard : metacards) {
      try {
        final Placemark placemark = new Placemark();
        final Geometry kmlGeoFromWkt = getKmlGeoFromWkt(metacard.getLocation(), false);
        placemark.setGeometry(kmlGeoFromWkt);
        setPlacemarkDescriptors(placemark, metacard);
        setExtendedData(placemark, metacard);
        placemarks.add(placemark);
      } catch (CatalogTransformerException e) {
        if (skipUntransformableItems) {
          LOGGER.debug(
              "Error transforming current metacard ({}) to KML and will continue with remaining "
                  + "metacards.",
              metacard.getId(),
              e);
        } else {
          throw e;
        }
      }
    }
    return kmlDoc;
  }

  private void setPlacemarkDescriptors(Placemark placemark, Metacard metacard) {
    placemark.setId("Placemark-" + metacard.getId());
    placemark.setName(metacard.getTitle());
  }

  /**
   * Add target metacard attributes to {@link Placemark}'s extended data to preserve future import
   * capabilities.
   */
  private void setExtendedData(Placemark placemark, Metacard metacard) {
    final ExtendedData extendedData = new ExtendedData();

    for (String attributeName : aliasMap.keySet()) {
      final Attribute attribute = metacard.getAttribute(attributeName);
      if (attribute != null) {
        final String attributeValue = (String) attribute.getValue();
        final String attributeAlias = aliasMap.get(attributeName);
        final Data data = getData(attributeAlias, attributeValue);
        extendedData.addToData(data);
      }
    }

    placemark.setExtendedData(extendedData);
  }

  private Data getData(String attributeAlias, String attributeValue) {
    final Data data = new Data(attributeValue);
    data.setName(attributeAlias);
    return data;
  }

  private String getDocName(Map<String, ? extends Serializable> arguments) {
    final String docNameArgument = (String) arguments.get(DOC_NAME_ARG_KEY);
    return docNameArgument != null ? docNameArgument : KML_DOC_NAME_DEFAULT;
  }

  /**
   * @param headerAliases - Set of alias strings ex. security.classification=Classification that are
   *     parsed into map containing key pairs of the form Classification: security.classification
   */
  public void setHeaderAliases(List<String> headerAliases) {
    final Map<String, String> tempAliasMap = new HashMap<>();
    for (String alias : headerAliases) {
      final String[] split = alias.split("=", 2);
      if (split.length == 2) {
        tempAliasMap.put(split[0], split[1]);
      } else {
        LOGGER.debug("Alias entry '{}' did not contain '=' sign, skipping entry.", alias);
      }
    }

    aliasMap = ImmutableMap.copyOf(tempAliasMap);
  }

  boolean getArgumentAsBoolean(Map<String, ? extends Serializable> arguments, String argumentName)
      throws CatalogTransformerException {

    if (arguments == null) {
      throw new CatalogTransformerException("Argument map cannot be null");
    }

    final Serializable value = arguments.get(argumentName);
    if (value instanceof Boolean) {
      return (boolean) value;
    } else if (value instanceof String[] && ((String[]) value).length > 0) {
      return Boolean.valueOf(((String[]) value)[0]);
    } else if (value instanceof String) {
      return Boolean.valueOf((String) value);
    } else {
      return false;
    }
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public Set<MimeType> getMimeTypes() {
    return mimeTypes;
  }

  @Override
  public Map<String, Object> getProperties() {
    return new ImmutableMap.Builder<String, Object>()
        .put(Constants.SERVICE_ID, getId())
        .put(MIME_TYPE, getMimeTypes())
        .build();
  }
}
