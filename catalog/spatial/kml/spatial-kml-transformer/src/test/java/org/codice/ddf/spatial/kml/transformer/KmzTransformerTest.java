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
import static org.codice.ddf.spatial.kml.transformer.KmzInputTransformer.KML_EXTENSION;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import ddf.catalog.data.BinaryContent;
import ddf.catalog.data.impl.BinaryContentImpl;
import ddf.catalog.transform.CatalogTransformerException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;

public class KmzTransformerTest {

  private KmzTransformer kmzTransformer;

  @Mock private KMLTransformerImpl kmlTransformer;

  private static String kmlInput1, kmlInput2;

  @BeforeClass
  public static void setupClass() throws IOException {
    kmlInput1 = resourceToString("/KmzTransformerTest/kmlInput1.kml");
    kmlInput2 = resourceToString("/KmzTransformerTest/kmlInput2.kml");
  }

  @Before
  public void setup() throws CatalogTransformerException {
    initMocks(this);
    kmzTransformer = new KmzTransformer(kmlTransformer, "kmz");

    InputStream kmlInputStream1 =
        new ByteArrayInputStream(kmlInput1.getBytes(StandardCharsets.UTF_8));

    InputStream kmlInputStream2 =
        new ByteArrayInputStream(kmlInput2.getBytes(StandardCharsets.UTF_8));

    BinaryContent bc1 = new BinaryContentImpl(kmlInputStream1);
    BinaryContent bc2 = new BinaryContentImpl(kmlInputStream2);

    final List<BinaryContent> binaryContents = asList(bc1, bc2);

    when(kmlTransformer.transform(anyList(), anyMap())).thenReturn(binaryContents);
  }

  @Test
  public void testKmzTransform() throws CatalogTransformerException, IOException {

    // NOTE: Response from kml transformer is mocked, metacard list passed in is not used.
    List<BinaryContent> transform = kmzTransformer.transform(Collections.singletonList(null), null);
    assertThat(transform, hasSize(2));

    String outputKml1 = getOutputFromBinaryContent(transform.get(0));
    String outputKml2 = getOutputFromBinaryContent(transform.get(1));

    assertThat(outputKml1, is(kmlInput1));
    assertThat(outputKml2, is(kmlInput2));
  }

  private String getOutputFromBinaryContent(BinaryContent bc) throws IOException {

    // BC is a kmz zip file containing a single kml file called doc.kml.
    // Optionally, relative file links will exist in folder called files
    String outputKml;
    try (ZipInputStream zipInputStream = new ZipInputStream(bc.getInputStream())) {

      ZipEntry entry;
      outputKml = "";
      while ((entry = zipInputStream.getNextEntry()) != null) {

        // According to Google, a .kmz should only contain a single .kml file
        // so we stop at the first one we find.
        final String fileName = entry.getName();
        if (fileName.endsWith(KML_EXTENSION)) {
          assertThat(fileName, is("doc.kml"));
          outputKml = readContentsFromZipInputStream(zipInputStream);
          break;
        }
      }
    }
    return outputKml;
  }

  private String readContentsFromZipInputStream(ZipInputStream zipInputStream) throws IOException {
    String s = IOUtils.toString(zipInputStream, StandardCharsets.UTF_8.name());

    // Close the zip input stream.
    IOUtils.closeQuietly(zipInputStream);

    return s;
  }

  private static String resourceToString(String resourceName) throws IOException {
    try (final InputStream inputStream =
        KmzTransformerTest.class.getResourceAsStream(resourceName)) {
      return IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
    }
  }
}
