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

import static org.codice.ddf.spatial.ogc.wfs.transformer.handlebars.HandlebarsWfsFeatureTransformer.ATTRIBUTE_NAME;
import static org.codice.ddf.spatial.ogc.wfs.transformer.handlebars.HandlebarsWfsFeatureTransformer.FEATURE_NAME;
import static org.codice.ddf.spatial.ogc.wfs.transformer.handlebars.HandlebarsWfsFeatureTransformer.TEMPLATE;
import static org.junit.Assert.assertTrue;

import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.types.CoreAttributes;
import ddf.catalog.data.impl.types.LocationAttributes;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.codice.ddf.spatial.ogc.wfs.featuretransformer.WfsMetadata;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HandlebarsWfsFeatureTransformerTest {
  private HandlebarsWfsFeatureTransformer transformer;
  //  @Mock private AttributeRegistry attributeRegistry;
  @Mock private WfsMetadata wfsMetadata;
  private static final String EXPECTED_FEATURE_TYPE = "http://www.neverland.org/peter/pan";
  private static final String MAPPING_FORMAT =
      "{ \""
          + ATTRIBUTE_NAME
          + "\":\"%s\", \""
          + FEATURE_NAME
          + "\":\"%s\", \""
          + TEMPLATE
          + "\":\"%s\"}";

  private static final CoreAttributes CORE_ATTRIBUTES = new CoreAttributes();
  private static final LocationAttributes LOCATION_ATTRIBUTES = new LocationAttributes();

  @Before
  public void setup() {
    transformer = new HandlebarsWfsFeatureTransformer();
    transformer.setDataUnit("G");
    transformer.setFeatureType(EXPECTED_FEATURE_TYPE);
    transformer.setAttributeMappings(getMappings());
    //    createAttributeRegistry();
    //    transformer.setAttributeRegistry(attributeRegistry);
  }

  @Test
  public void testRead() {
    InputStream inputStream =
        new BufferedInputStream(
            HandlebarsWfsFeatureTransformer.class.getResourceAsStream("/FeatureMember.xml"));
    Optional<Metacard> metacardOptional = transformer.apply(inputStream, wfsMetadata);

    assertTrue(metacardOptional.isPresent());
  }

  private List<String> getMappings() {

    return Arrays.asList(
        createMapping(LocationAttributes.COUNTRY_CODE, "CountryCode", "CC - {{CountryCode}}"),
        createMapping(CoreAttributes.LOCATION, "SpatialData", "Location - {{SpatialData}}"));
  }

  private String createMapping(String attributeName, String featureName, String template) {
    return String.format(MAPPING_FORMAT, attributeName, featureName, template);
  }

  //  private void createAttributeRegistry() {
  //    when(attributeRegistry.lookup(CoreAttributes.LOCATION))
  //
  // .thenReturn(Optional.of(CORE_ATTRIBUTES.getAttributeDescriptor(CoreAttributes.LOCATION)));
  //    when(attributeRegistry.lookup(LocationAttributes.COUNTRY_CODE))
  //        .thenReturn(
  //            Optional.of(
  //                LOCATION_ATTRIBUTES.getAttributeDescriptor(LocationAttributes.COUNTRY_CODE)));
  //  }
}
