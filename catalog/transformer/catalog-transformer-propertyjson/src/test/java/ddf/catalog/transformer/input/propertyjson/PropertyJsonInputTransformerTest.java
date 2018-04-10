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
package ddf.catalog.transformer.input.propertyjson;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ddf.catalog.data.AttributeRegistry;
import ddf.catalog.data.AttributeType;
import ddf.catalog.data.InjectableAttribute;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardType;
import ddf.catalog.data.impl.AttributeDescriptorImpl;
import ddf.catalog.data.impl.BasicTypes;
import ddf.catalog.data.impl.MetacardTypeImpl;
import ddf.catalog.data.impl.types.AssociationsAttributes;
import ddf.catalog.data.impl.types.ContactAttributes;
import ddf.catalog.data.impl.types.CoreAttributes;
import ddf.catalog.data.impl.types.DateTimeAttributes;
import ddf.catalog.data.impl.types.MediaAttributes;
import ddf.catalog.data.impl.types.TopicAttributes;
import ddf.catalog.data.impl.types.ValidationAttributes;
import ddf.catalog.data.impl.types.experimental.ExtractedAttributes;
import ddf.catalog.data.types.Core;
import ddf.catalog.data.types.Media;
import ddf.catalog.transform.CatalogTransformerException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import org.junit.Before;
import org.junit.Test;

public class PropertyJsonInputTransformerTest {

  private static final String ISO_8601_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

  private List<InjectableAttribute> injectableAttributes;

  private AttributeRegistry attributeRegistry;

  private MetacardType fallbackCommon;

  private static final String METACARD_TYPE_NAME = "fallback.common";

  @Before
  public void setup() {
    injectableAttributes = new LinkedList<>();
    attributeRegistry = mock(AttributeRegistry.class);

    createInjectableAttributes();

    fallbackCommon =
        new MetacardTypeImpl(
            METACARD_TYPE_NAME,
            Arrays.asList(
                new TopicAttributes(),
                new MediaAttributes(),
                new ContactAttributes(),
                new AssociationsAttributes(),
                new DateTimeAttributes(),
                new CoreAttributes(),
                new ExtractedAttributes(),
                new ValidationAttributes()));
  }

  private void createInjectableAttributes() {
    createInjectableAttribute(Metacard.EFFECTIVE, BasicTypes.DATE_TYPE);
    createInjectableAttribute(Metacard.POINT_OF_CONTACT, BasicTypes.STRING_TYPE);
    createInjectableAttribute("integer.to.long", BasicTypes.LONG_TYPE);
    createInjectableAttribute("double.to.float", BasicTypes.FLOAT_TYPE);
    createInjectableAttribute("integer.to.float", BasicTypes.FLOAT_TYPE);
    createInjectableAttribute("integer.to.double", BasicTypes.DOUBLE_TYPE);
    createInjectableAttribute("integer.to.short", BasicTypes.SHORT_TYPE);
    createInjectableAttribute("integer.to.string", BasicTypes.STRING_TYPE);
    createInjectableAttribute("double.to.integer", BasicTypes.INTEGER_TYPE);
    createInjectableAttribute("double.to.long", BasicTypes.LONG_TYPE);
    createInjectableAttribute("double.to.short", BasicTypes.SHORT_TYPE);
    createInjectableAttribute("double.to.double", BasicTypes.DOUBLE_TYPE);
    createInjectableAttribute("double.to.string", BasicTypes.STRING_TYPE);
    createInjectableAttribute("string.to.boolean", BasicTypes.BOOLEAN_TYPE);
    createInjectableAttribute("string.to.short", BasicTypes.SHORT_TYPE);
    createInjectableAttribute("string.to.integer", BasicTypes.INTEGER_TYPE);
    createInjectableAttribute("string.to.long", BasicTypes.LONG_TYPE);
    createInjectableAttribute("string.to.float", BasicTypes.FLOAT_TYPE);
    createInjectableAttribute("string.to.double", BasicTypes.DOUBLE_TYPE);
  }

  private void createInjectableAttribute(String attribute, AttributeType<?> type) {
    createInjectableAttribute(Collections.singleton(METACARD_TYPE_NAME), attribute, type);
  }

  private void createInjectableAttribute(
      Set<String> appliesTo, String attribute, AttributeType<?> type) {
    InjectableAttribute effectiveAttribute = mock(InjectableAttribute.class);
    when(effectiveAttribute.metacardTypes()).thenReturn(appliesTo);
    when(effectiveAttribute.attribute()).thenReturn(attribute);
    injectableAttributes.add(effectiveAttribute);

    when(attributeRegistry.lookup(attribute))
        .thenReturn(
            Optional.of(new AttributeDescriptorImpl(attribute, false, false, false, false, type)));
  }

  @Test
  public void testBasic() throws IOException, CatalogTransformerException, ParseException {

    try (InputStream inputStream = getResource("/basic-example.json")) {

      PropertyJsonInputTransformer propertyJsonInputTransformer =
          new PropertyJsonInputTransformer(fallbackCommon, injectableAttributes, attributeRegistry);

      propertyJsonInputTransformer.addMetacardType(fallbackCommon);

      Metacard metacard = propertyJsonInputTransformer.transform(inputStream);

      assertThat(metacard.getResourceSize(), is("70899"));
      assertThat(metacard.getMetadata(), is("some metadata"));
      assertThat(metacard.getCreatedDate(), is(parseDate("2018-03-21T15:33:51.341+0000")));
      assertThat(metacard.getTags(), is(new HashSet<>(Arrays.asList("resource", "VALID"))));
      assertThat(
          metacard.getResourceURI(), is(URI.create("content:047e2b8db1fc4a68837e97a38f561d22")));
      assertValues(metacard, Core.CHECKSUM_ALGORITHM, "Adler32");
      assertValues(metacard, Media.TYPE, "image/png");
      assertValues(metacard, Media.WIDTH, 305);
      assertThat(metacard.getMetacardType().getName(), is("fallback.common"));
      assertThat(metacard.getTitle(), is("screen_shot_2018-03-20_at_11.26.40_am.png"));
      assertValues(
          metacard,
          Core.RESOURCE_DOWNLOAD_URL,
          "https://localhost:8993/services/catalog/sources/ddf.distribution/047e2b8db1fc4a68837e97a38f561d22?transform=resource");
      assertThat(metacard.getSourceId(), is("ddf.distribution"));
      assertValues(metacard, Core.METACARD_CREATED, parseDate("2018-03-21T15:33:51.341+0000"));
      assertThat(metacard.getEffectiveDate(), is(parseDate("2018-03-21T15:33:51.341+0000")));
      assertValues(metacard, Media.HEIGHT, 581);
      assertValues(metacard, Metacard.POINT_OF_CONTACT, "admin@localhost");
      assertValues(metacard, Core.DATATYPE, "Image");
      assertValues(metacard, Core.METACARD_MODIFIED, parseDate("2018-03-21T15:33:51.341+0000"));
      assertValues(metacard, Core.CHECKSUM, "be451044");
      assertThat(metacard.getModifiedDate(), is(parseDate("2018-03-21T15:33:51.341+0000")));
      assertThat(metacard.getId(), is("047e2b8db1fc4a68837e97a38f561d22"));
      assertValues(metacard, "integer.to.long", 12345L);
      assertValues(metacard, "double.to.float", 3.14f);
      assertValues(metacard, "integer.to.float", 3f);
      assertValues(metacard, "integer.to.double", 3d);
      assertValues(metacard, "integer.to.short", (short) 5);
      assertValues(metacard, "integer.to.string", "7");
      assertValues(metacard, "double.to.integer", 4);
      assertValues(metacard, "double.to.long", 5L);
      assertValues(metacard, "double.to.short", (short) 8);
      assertValues(metacard, "double.to.double", 9.3d);
      assertValues(metacard, "double.to.string", "9.5");
      assertValues(metacard, "string.to.short", (short) 5);
      assertValues(metacard, "string.to.boolean", false);
      assertValues(metacard, "string.to.integer", 7);
      assertValues(metacard, "string.to.long", 20L);
      assertValues(metacard, "string.to.float", 3.22f);
      assertValues(metacard, "string.to.double", 3.3);
    }
  }

  private <T> void assertValues(Metacard metacard, String attribute, T value) {
    assertThat(metacard.getAttribute(attribute).getValues(), is(Collections.singletonList(value)));
  }

  private Date parseDate(String dateString) throws ParseException {
    SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_8601_DATE_FORMAT);
    dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    return dateFormat.parse(dateString);
  }

  private InputStream getResource(String resourceName) {
    return PropertyJsonInputTransformerTest.class.getResourceAsStream(resourceName);
  }
}
