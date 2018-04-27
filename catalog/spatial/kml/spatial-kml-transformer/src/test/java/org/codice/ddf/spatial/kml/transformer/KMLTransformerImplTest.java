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

import static org.codice.ddf.spatial.kml.transformer.KMLTransformerImpl.DOC_NAME_ARG;
import static org.codice.ddf.spatial.kml.transformer.KMLTransformerImpl.SKIP_UNTRANSFORMABLE_ITEMS_ARG;
import static org.custommonkey.xmlunit.XMLAssert.assertXpathEvaluatesTo;
import static org.custommonkey.xmlunit.XMLAssert.assertXpathExists;
import static org.custommonkey.xmlunit.XMLAssert.assertXpathNotExists;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ddf.action.Action;
import ddf.action.ActionProvider;
import ddf.catalog.data.BinaryContent;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.transform.CatalogTransformerException;
import de.micromata.opengis.kml.v_2_2_0.LineString;
import de.micromata.opengis.kml.v_2_2_0.MultiGeometry;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.Point;
import de.micromata.opengis.kml.v_2_2_0.Polygon;
import de.micromata.opengis.kml.v_2_2_0.TimeSpan;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.commons.io.IOUtils;
import org.custommonkey.xmlunit.NamespaceContext;
import org.custommonkey.xmlunit.SimpleNamespaceContext;
import org.custommonkey.xmlunit.XMLUnit;
import org.custommonkey.xmlunit.exceptions.XpathException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.xml.sax.SAXException;

public class KMLTransformerImplTest {

  private static final String DEFAULT_STYLE_LOCATION = "/kml-styling/defaultStyling.kml";

  private static final String ID = "1234567890";

  private static final String TITLE = "myTitle";

  private static final String POINT_WKT = "POINT (-110.00540924072266 34.265270233154297)";

  private static final String LINESTRING_WKT = "LINESTRING (1 1,2 1)";

  private static final String POLYGON_WKT = "POLYGON ((1 1,2 1,2 2,1 2,1 1))";

  private static final String MULTIPOINT_WKT = "MULTIPOINT ((1 1), (0 0), (2 2))";

  private static final String MULTILINESTRING_WKT = "MULTILINESTRING ((1 1, 2 1), (1 2, 0 0))";

  private static final String MULTIPOLYGON_WKT =
      "MULTIPOLYGON (((1 1,2 1,2 2,1 2,1 1)), ((0 0,1 1,2 0,0 0)))";

  private static final String GEOMETRYCOLLECTION_WKT =
      "GEOMETRYCOLLECTION (" + POINT_WKT + ", " + LINESTRING_WKT + ", " + POLYGON_WKT + ")";

  private static final String ACTION_URL = "http://example.com/source/id?transform=resource";

  private static BundleContext mockContext = mock(BundleContext.class);

  private static Bundle mockBundle = mock(Bundle.class);

  private static KMLTransformerImpl kmlTransformer;

  private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

  private static final String DOC_NAME_DEFAULT = "KML Metacard Export";

  @BeforeClass
  public static void setUp() throws IOException {
    when(mockContext.getBundle()).thenReturn(mockBundle);
    URL url = KMLTransformerImplTest.class.getResource(DEFAULT_STYLE_LOCATION);
    when(mockBundle.getResource(any(String.class))).thenReturn(url);

    dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    ActionProvider mockActionProvider = mock(ActionProvider.class);
    Action mockAction = mock(Action.class);
    when(mockActionProvider.getAction(any(Metacard.class))).thenReturn(mockAction);
    when(mockAction.getUrl()).thenReturn(new URL(ACTION_URL));
    kmlTransformer =
        new KMLTransformerImpl(
            mockContext, DEFAULT_STYLE_LOCATION, new KmlStyleMap(), mockActionProvider);
  }

  @Before
  public void setupXpath() {
    Map<String, String> m = new HashMap<String, String>();
    m.put("m", "http://www.opengis.net/kml/2.2");
    NamespaceContext ctx = new SimpleNamespaceContext(m);
    XMLUnit.setXpathNamespaceContext(ctx);
  }

  @Test(expected = CatalogTransformerException.class)
  public void testPerformDefaultTransformationNoLocation() throws CatalogTransformerException {
    Metacard metacard = createMockMetacard();
    kmlTransformer.performDefaultTransformation(metacard, null);
  }

  @Test
  public void testPerformDefaultTransformationPointLocation() throws CatalogTransformerException {
    MetacardImpl metacard = createMockMetacard();
    metacard.setLocation(POINT_WKT);
    Placemark placemark = kmlTransformer.performDefaultTransformation(metacard, null);
    assertThat(placemark.getId(), is("Placemark-" + ID));
    assertThat(placemark.getName(), is(TITLE));
    assertThat(placemark.getStyleSelector().isEmpty(), is(true));
    assertThat(placemark.getStyleUrl(), nullValue());
    assertThat(placemark.getTimePrimitive(), instanceOf(TimeSpan.class));
    TimeSpan timeSpan = (TimeSpan) placemark.getTimePrimitive();
    assertThat(timeSpan.getBegin(), is(dateFormat.format(metacard.getEffectiveDate())));
    assertThat(placemark.getGeometry(), instanceOf(Point.class));
  }

  @Test
  public void testPerformDefaultTransformationLineStringLocation()
      throws CatalogTransformerException {
    MetacardImpl metacard = createMockMetacard();
    metacard.setLocation(LINESTRING_WKT);
    Placemark placemark = kmlTransformer.performDefaultTransformation(metacard, null);
    assertThat(placemark.getId(), is("Placemark-" + ID));
    assertThat(placemark.getName(), is(TITLE));
    assertThat(placemark.getTimePrimitive(), instanceOf(TimeSpan.class));
    TimeSpan timeSpan = (TimeSpan) placemark.getTimePrimitive();
    assertThat(timeSpan.getBegin(), is(dateFormat.format(metacard.getEffectiveDate())));
    assertThat(placemark.getGeometry(), instanceOf(MultiGeometry.class));
    MultiGeometry multiGeo = (MultiGeometry) placemark.getGeometry();
    assertThat(multiGeo.getGeometry().size(), is(2));
    assertThat(multiGeo.getGeometry().get(0), instanceOf(Point.class));
    assertThat(multiGeo.getGeometry().get(1), instanceOf(LineString.class));
  }

  @Test
  public void testPerformDefaultTransformationPolygonLocation() throws CatalogTransformerException {
    MetacardImpl metacard = createMockMetacard();
    metacard.setLocation(POLYGON_WKT);
    Placemark placemark = kmlTransformer.performDefaultTransformation(metacard, null);
    assertThat(placemark.getId(), is("Placemark-" + ID));
    assertThat(placemark.getName(), is(TITLE));
    assertThat(placemark.getTimePrimitive(), instanceOf(TimeSpan.class));
    TimeSpan timeSpan = (TimeSpan) placemark.getTimePrimitive();
    assertThat(timeSpan.getBegin(), is(dateFormat.format(metacard.getEffectiveDate())));
    assertThat(placemark.getGeometry(), instanceOf(MultiGeometry.class));
    MultiGeometry multiGeo = (MultiGeometry) placemark.getGeometry();
    assertThat(multiGeo.getGeometry().size(), is(2));
    assertThat(multiGeo.getGeometry().get(0), instanceOf(Point.class));
    assertThat(multiGeo.getGeometry().get(1), instanceOf(Polygon.class));
  }

  @Test
  public void testPerformDefaultTransformationMultiPointLocation()
      throws CatalogTransformerException {
    MetacardImpl metacard = createMockMetacard();
    metacard.setLocation(MULTIPOINT_WKT);
    Placemark placemark = kmlTransformer.performDefaultTransformation(metacard, null);
    assertThat(placemark.getId(), is("Placemark-" + ID));
    assertThat(placemark.getName(), is(TITLE));
    assertThat(placemark.getTimePrimitive(), instanceOf(TimeSpan.class));
    TimeSpan timeSpan = (TimeSpan) placemark.getTimePrimitive();
    assertThat(timeSpan.getBegin(), is(dateFormat.format(metacard.getEffectiveDate())));
    assertThat(placemark.getGeometry(), instanceOf(MultiGeometry.class));
    MultiGeometry multiGeo = (MultiGeometry) placemark.getGeometry();
    assertThat(multiGeo.getGeometry().size(), is(2));
    assertThat(multiGeo.getGeometry().get(0), instanceOf(Point.class));
    assertThat(multiGeo.getGeometry().get(1), instanceOf(MultiGeometry.class));
    MultiGeometry multiPoint = (MultiGeometry) multiGeo.getGeometry().get(1);
    assertThat(multiPoint.getGeometry().size(), is(3));
    assertThat(multiPoint.getGeometry().get(0), instanceOf(Point.class));
    assertThat(multiPoint.getGeometry().get(1), instanceOf(Point.class));
    assertThat(multiPoint.getGeometry().get(2), instanceOf(Point.class));
  }

  @Test
  public void testPerformDefaultTransformationMultiLineStringLocation()
      throws CatalogTransformerException {
    MetacardImpl metacard = createMockMetacard();
    metacard.setLocation(MULTILINESTRING_WKT);
    Placemark placemark = kmlTransformer.performDefaultTransformation(metacard, null);
    assertThat(placemark.getId(), is("Placemark-" + ID));
    assertThat(placemark.getName(), is(TITLE));
    assertThat(placemark.getTimePrimitive(), instanceOf(TimeSpan.class));
    TimeSpan timeSpan = (TimeSpan) placemark.getTimePrimitive();
    assertThat(timeSpan.getBegin(), is(dateFormat.format(metacard.getEffectiveDate())));
    assertThat(placemark.getGeometry(), instanceOf(MultiGeometry.class));
    MultiGeometry multiGeo = (MultiGeometry) placemark.getGeometry();
    assertThat(multiGeo.getGeometry().size(), is(2));
    assertThat(multiGeo.getGeometry().get(0), instanceOf(Point.class));
    assertThat(multiGeo.getGeometry().get(1), instanceOf(MultiGeometry.class));
    MultiGeometry multiLineString = (MultiGeometry) multiGeo.getGeometry().get(1);
    assertThat(multiLineString.getGeometry().size(), is(2));
    assertThat(multiLineString.getGeometry().get(0), instanceOf(LineString.class));
    assertThat(multiLineString.getGeometry().get(1), instanceOf(LineString.class));
  }

  @Test
  public void testPerformDefaultTransformationMultiPolygonLocation()
      throws CatalogTransformerException {
    MetacardImpl metacard = createMockMetacard();
    metacard.setLocation(MULTIPOLYGON_WKT);
    Placemark placemark = kmlTransformer.performDefaultTransformation(metacard, null);
    assertThat(placemark.getId(), is("Placemark-" + ID));
    assertThat(placemark.getName(), is(TITLE));
    assertThat(placemark.getTimePrimitive(), instanceOf(TimeSpan.class));
    TimeSpan timeSpan = (TimeSpan) placemark.getTimePrimitive();
    assertThat(timeSpan.getBegin(), is(dateFormat.format(metacard.getEffectiveDate())));
    assertThat(placemark.getGeometry(), instanceOf(MultiGeometry.class));
    MultiGeometry multiGeo = (MultiGeometry) placemark.getGeometry();
    assertThat(multiGeo.getGeometry().size(), is(2));
    assertThat(multiGeo.getGeometry().get(0), instanceOf(Point.class));
    assertThat(multiGeo.getGeometry().get(1), instanceOf(MultiGeometry.class));
    MultiGeometry multiPolygon = (MultiGeometry) multiGeo.getGeometry().get(1);
    assertThat(multiPolygon.getGeometry().size(), is(2));
    assertThat(multiPolygon.getGeometry().get(0), instanceOf(Polygon.class));
    assertThat(multiPolygon.getGeometry().get(1), instanceOf(Polygon.class));
  }

  @Test
  public void testPerformDefaultTransformationGeometryCollectionLocation()
      throws CatalogTransformerException {
    MetacardImpl metacard = createMockMetacard();
    metacard.setLocation(GEOMETRYCOLLECTION_WKT);
    Placemark placemark = kmlTransformer.performDefaultTransformation(metacard, null);
    assertThat(placemark.getId(), is("Placemark-" + ID));
    assertThat(placemark.getName(), is(TITLE));
    assertThat(placemark.getTimePrimitive(), instanceOf(TimeSpan.class));
    TimeSpan timeSpan = (TimeSpan) placemark.getTimePrimitive();
    assertThat(timeSpan.getBegin(), is(dateFormat.format(metacard.getEffectiveDate())));
    assertThat(placemark.getGeometry(), instanceOf(MultiGeometry.class));
    MultiGeometry multiGeo = (MultiGeometry) placemark.getGeometry();
    assertThat(multiGeo.getGeometry().size(), is(2));
    assertThat(multiGeo.getGeometry().get(0), instanceOf(Point.class));
    assertThat(multiGeo.getGeometry().get(1), instanceOf(MultiGeometry.class));
    MultiGeometry multiGeo2 = (MultiGeometry) multiGeo.getGeometry().get(1);
    assertThat(multiGeo2.getGeometry().size(), is(3));
    assertThat(multiGeo2.getGeometry().get(0), instanceOf(Point.class));
    assertThat(multiGeo2.getGeometry().get(1), instanceOf(LineString.class));
    assertThat(multiGeo2.getGeometry().get(2), instanceOf(Polygon.class));
  }

  @Test
  public void testTransformMetacardGetsDefaultStyle()
      throws CatalogTransformerException, IOException {
    MetacardImpl metacard = createMockMetacard();
    metacard.setLocation(POINT_WKT);
    BinaryContent content = kmlTransformer.transform(metacard, null);
    assertThat(content.getMimeTypeValue(), is(KMLTransformerImpl.KML_MIMETYPE.toString()));
    IOUtils.toString(content.getInputStream());
  }

  // Tests that an invalid metacard causes an exception to be thrown.
  @Test(expected = CatalogTransformerException.class)
  public void testTransformMetacardListThrowException() throws CatalogTransformerException {
    List<Metacard> metacardList = getMetacards();
    Map<String, String> arguments = Collections.singletonMap(DOC_NAME_ARG, DOC_NAME_DEFAULT);
    kmlTransformer.transform(metacardList, arguments);
  }

  // Tests that an invalid metacard does not throw an exception, but is instead
  // skipped because the skipUntransformableItems argument is given.
  @Test
  public void testTransformMetacardListSkipUntransformable()
      throws CatalogTransformerException, IOException, XpathException, SAXException {
    List<Metacard> metacardList = getMetacards();

    Map<String, Serializable> args = new HashMap<>();
    args.put(DOC_NAME_ARG, DOC_NAME_DEFAULT);
    args.put(SKIP_UNTRANSFORMABLE_ITEMS_ARG, true);

    List<BinaryContent> bc = kmlTransformer.transform(metacardList, args);
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
    assertXpathEvaluatesTo(DOC_NAME_DEFAULT, "//m:Document/m:name", outputKml);
    assertXpathExists("//m:Placemark[@id='Placemark-UUID-1']/m:name", outputKml);
    assertXpathExists("//m:Placemark[@id='Placemark-UUID-2']/m:name", outputKml);
    assertXpathNotExists("//m:Placemark[@id='Placemark-UUID-3']/m:name", outputKml);
  }

  // Returns a list of metacards for testing, 2 valid and 1 invalid.
  private List<Metacard> getMetacards() {
    List<Metacard> metacardList = new ArrayList<>();

    MetacardImpl metacard1 = createMockMetacard();
    metacard1.setId("UUID-1");
    metacard1.setTitle("ASU");
    metacard1.setLocation("POINT (-111.9281 33.4242)");
    metacardList.add(metacard1);

    MetacardImpl metacard2 = createMockMetacard();
    metacard2.setId("UUID-2");
    metacard2.setTitle("Cardinals Stadium");
    metacard2.setLocation("POINT (-112.2626 33.5276)");
    metacardList.add(metacard2);

    MetacardImpl metacard3 = createMockMetacard();
    metacard3.setId("UUID-3");
    metacard3.setTitle("Invalid Metacard");
    metacard3.setLocation("UNKNOWN");
    metacardList.add(metacard3);
    return metacardList;
  }

  private MetacardImpl createMockMetacard() {
    MetacardImpl metacard = new MetacardImpl();
    metacard.setContentTypeName("myContentType");
    metacard.setContentTypeVersion("myVersion");
    metacard.setCreatedDate(Calendar.getInstance().getTime());
    metacard.setEffectiveDate(Calendar.getInstance().getTime());
    metacard.setExpirationDate(Calendar.getInstance().getTime());
    metacard.setId("1234567890");
    // metacard.setLocation(wkt);
    metacard.setMetadata("<xml>Metadata</xml>");
    metacard.setModifiedDate(Calendar.getInstance().getTime());
    // metacard.setResourceSize("10MB");
    // metacard.setResourceURI(uri)
    metacard.setSourceId("sourceID");
    metacard.setTitle("myTitle");
    return metacard;
  }
}
