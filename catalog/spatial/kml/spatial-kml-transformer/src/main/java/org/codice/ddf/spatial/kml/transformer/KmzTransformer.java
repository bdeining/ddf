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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableMap;
import ddf.catalog.Constants;
import ddf.catalog.data.BinaryContent;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.BinaryContentImpl;
import ddf.catalog.transform.CatalogTransformerException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import org.apache.commons.io.IOUtils;
import org.codice.ddf.catalog.transform.MultiMetacardTransformer;
import org.codice.ddf.platform.util.TemporaryFileBackedOutputStream;
import org.springframework.util.CollectionUtils;

public class KmzTransformer implements MultiMetacardTransformer {

  private static final MimeType KMZ_MIMETYPE;
  private KMLTransformerImpl kmlTransformer;
  private String transformerId;

  private static final String DOC_KML = "doc.kml";

  static {
    try {
      KMZ_MIMETYPE = new MimeType("application/vnd.google-earth.kmz");
    } catch (MimeTypeParseException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public KmzTransformer(KMLTransformerImpl kmlTransformer, String id) {
    this.kmlTransformer = checkNotNull(kmlTransformer);
    this.transformerId = id;
  }

  @Override
  public List<BinaryContent> transform(
      List<Metacard> metacards, Map<String, ? extends Serializable> arguments)
      throws CatalogTransformerException {

    if (CollectionUtils.isEmpty(metacards)) {
      throw new CatalogTransformerException("Metacard list cannot be null or empty.");
    }

    try {
      return getBinaryContentList(metacards, arguments);
    } catch (IOException e) {
      throw new CatalogTransformerException("Could not transform metacards to kmz file.", e);
    }
  }

  private List<BinaryContent> getBinaryContentList(
      List<Metacard> metacards, Map<String, ? extends Serializable> arguments)
      throws IOException, CatalogTransformerException {

    final List<BinaryContent> kmzBinaryContents = new ArrayList<>();

    // Get kml file input stream.
    final List<BinaryContent> kmlBinaryContents = kmlTransformer.transform(metacards, arguments);

    for (BinaryContent kmlBinaryContent : kmlBinaryContents) {

      try (InputStream inputStream = kmlBinaryContent.getInputStream();

          // Create a temporary file to hold kml document.
          TemporaryFileBackedOutputStream temporaryFileBackedOutputStream =
              new TemporaryFileBackedOutputStream();
          ZipOutputStream zipOutputStream = new ZipOutputStream(temporaryFileBackedOutputStream)) {

        addToKmzFile(DOC_KML, inputStream, zipOutputStream);

        zipOutputStream.finish();

        final byte[] read = temporaryFileBackedOutputStream.asByteSource().read();

        kmzBinaryContents.add(new BinaryContentImpl(new ByteArrayInputStream(read), KMZ_MIMETYPE));
      }
    }

    return kmzBinaryContents;
  }

  private void addToKmzFile(
      String fileName, InputStream inputStream, ZipOutputStream zipOutputStream)
      throws IOException {

    final ZipEntry e = new ZipEntry(fileName);
    zipOutputStream.putNextEntry(e);
    IOUtils.copy(inputStream, zipOutputStream);
    zipOutputStream.closeEntry();
  }

  @Override
  public String getId() {
    return transformerId;
  }

  @Override
  public Set<MimeType> getMimeTypes() {
    return Collections.singleton(KMZ_MIMETYPE);
  }

  @Override
  public Map<String, Object> getProperties() {
    return new ImmutableMap.Builder<String, Object>()
        .put(Constants.SERVICE_ID, getId())
        .put("mime-type", getMimeTypes())
        .build();
  }
}
