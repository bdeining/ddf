/*
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

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static org.codice.ddf.spatial.kml.transformer.KMLTransformerImpl.DOC_NAME_ARG;
import static org.codice.ddf.spatial.kml.transformer.KMLTransformerImpl.SKIP_UNTRANSFORMABLE_ITEMS_ARG;
import static org.codice.ddf.spatial.kml.transformer.KMLTransformerImplTest.createMockMetacard;
import static org.codice.ddf.spatial.kml.transformer.KmlMultiMetacardTransformer.KML_DOC_NAME_DEFAULT;
import static org.custommonkey.xmlunit.XMLAssert.assertXpathEvaluatesTo;
import static org.custommonkey.xmlunit.XMLAssert.assertXpathExists;
import static org.custommonkey.xmlunit.XMLAssert.assertXpathNotExists;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import ddf.catalog.data.BinaryContent;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.data.types.Core;
import ddf.catalog.transform.CatalogTransformerException;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.custommonkey.xmlunit.NamespaceContext;
import org.custommonkey.xmlunit.SimpleNamespaceContext;
import org.custommonkey.xmlunit.XMLUnit;
import org.custommonkey.xmlunit.exceptions.XpathException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.SAXException;

public class KmlMultiMetacardTransformerTest {

  private KmlMultiMetacardTransformer kmlMultiMetacardTransformer;

  @Before
  public void setup() {
    kmlMultiMetacardTransformer = new KmlMultiMetacardTransformer("kml", emptySet());
    kmlMultiMetacardTransformer.setHeaderAliases(
        asList(
            String.format("%s=Name", Core.TITLE),
            String.format("%s=Description", Core.DESCRIPTION),
            String.format("%s=Coordinates", Core.LOCATION)));
  }

  @BeforeClass
  public static void setupXpath() {
    Map<String, String> m = new HashMap<String, String>();
    m.put("m", "http://www.opengis.net/kml/2.2");
    NamespaceContext ctx = new SimpleNamespaceContext(m);
    XMLUnit.setXpathNamespaceContext(ctx);
  }

  // Tests that an invalid metacard causes an exception to be thrown.
  @Test(expected = CatalogTransformerException.class)
  public void testTransformMetacardListThrowException() throws CatalogTransformerException {
    List<Metacard> metacardList = getMetacards();
    Map<String, String> arguments = Collections.singletonMap(DOC_NAME_ARG, KML_DOC_NAME_DEFAULT);
    kmlMultiMetacardTransformer.transform(metacardList, arguments);
  }

  // Tests that an invalid metacard does not throw an exception, but is instead
  // skipped because the skipUntransformableItems argument is given.
  @Test
  public void testTransformMetacardListSkipUntransformable()
      throws CatalogTransformerException, IOException, XpathException, SAXException {
    List<Metacard> metacardList = getMetacards();

    Map<String, Serializable> args = new HashMap<>();
    args.put(DOC_NAME_ARG, KML_DOC_NAME_DEFAULT);
    args.put(SKIP_UNTRANSFORMABLE_ITEMS_ARG, true);

    List<BinaryContent> bc = kmlMultiMetacardTransformer.transform(metacardList, args);
    assertThat(bc, hasSize(1));

    BinaryContent file = bc.get(0);
    assertThat(file.getMimeTypeValue(), is(KMLTransformerImpl.KML_MIMETYPE.toString()));

    String outputKml = new String(file.getByteArray());

    // Prefixing with a single slash indicates root. Two slashes means a PathExpression can match
    // anywhere no matter what the prefix is. For kml Xpath testing, the xmlns attribute of a kml
    // document must be set in the prefix map as 'm' in the @Before method and you must reference
    // fields in the document with that prefix like so.

    assertXpathExists("/m:kml", outputKml);
    assertXpathExists("//m:Document", outputKml);
    assertXpathEvaluatesTo(KML_DOC_NAME_DEFAULT, "//m:Document/m:name", outputKml);

    assertXpathEvaluatesTo(
        "Name-1", "/m:kml/m:Document/m:Placemark[@id='Placemark-UUID-1']/m:name", outputKml);
    assertXpathEvaluatesTo(
        "1.0,10.0",
        "/m:kml/m:Document/m:Placemark[@id='Placemark-UUID-1']/m:Point/m:coordinates",
        outputKml);
    assertExtendedDataExists("Placemark-UUID-1", "Name", "Name-1", outputKml);
    assertExtendedDataExists("Placemark-UUID-1", "Description", "Description-1", outputKml);
    assertExtendedDataExists("Placemark-UUID-1", "Coordinates", "POINT (1.0 10.0)", outputKml);

    assertXpathEvaluatesTo(
        "Name-2", "/m:kml/m:Document/m:Placemark[@id='Placemark-UUID-2']/m:name", outputKml);
    assertXpathEvaluatesTo(
        "2.0,20.0",
        "/m:kml/m:Document/m:Placemark[@id='Placemark-UUID-2']/m:Point/m:coordinates",
        outputKml);
    assertXpathExists("//m:Placemark[@id='Placemark-UUID-2']/m:name", outputKml);
    assertExtendedDataExists("Placemark-UUID-2", "Name", "Name-2", outputKml);
    assertExtendedDataExists("Placemark-UUID-2", "Description", "Description-2", outputKml);
    assertExtendedDataExists("Placemark-UUID-2", "Coordinates", "POINT (2.0 20.0)", outputKml);

    assertXpathNotExists("//m:Placemark[@id='Placemark-UUID-3']/m:name", outputKml);
  }

  private void assertExtendedDataExists(
      String placemarkId, String dataName, String dataValue, String kml)
      throws SAXException, IOException, XpathException {

    final String xPath =
        String.format(
            "/m:kml/m:Document/m:Placemark[@id='%s']/m:ExtendedData/m:Data[@name='%s']/m:value/text()",
            placemarkId, dataName);

    assertXpathEvaluatesTo(dataValue, xPath, kml);
  }

  // Returns a list of metacards for testing, 2 valid and 1 invalid.
  private List<Metacard> getMetacards() {
    List<Metacard> metacardList = new ArrayList<>();

    MetacardImpl metacard1 = createMockMetacard();
    metacard1.setId("UUID-1");
    metacard1.setTitle("Name-1");
    metacard1.setDescription("Description-1");
    metacard1.setLocation("POINT (1.0 10.0)");
    metacardList.add(metacard1);

    MetacardImpl metacard2 = createMockMetacard();
    metacard2.setId("UUID-2");
    metacard2.setTitle("Name-2");
    metacard2.setDescription("Description-2");
    metacard2.setLocation("POINT (2.0 20.0)");
    metacardList.add(metacard2);

    MetacardImpl metacard3 = createMockMetacard();
    metacard3.setId("UUID-3");
    metacard3.setTitle("Invalid Metacard");
    metacard3.setLocation("UNKNOWN");
    metacardList.add(metacard3);
    return metacardList;
  }
}
