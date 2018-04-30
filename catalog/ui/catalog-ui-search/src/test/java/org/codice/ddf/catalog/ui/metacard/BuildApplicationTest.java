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
package org.codice.ddf.catalog.ui.metacard;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import org.codice.ddf.catalog.ui.util.EndpointUtil;
import org.junit.Before;
import org.junit.Test;

public class BuildApplicationTest {

  private EndpointUtil endpointUtil;

  @Before
  public void setup() {
    endpointUtil = new EndpointUtil(null, null, null, null, null, null);
  }

  @Test
  public void testEmpty() {
    Map<String, Object> map = createBuildApplicationAndRun();
    assertThat(map, is(availableTypes()));
  }

  @Test
  public void testOneAvailableMetacardTypeWithOneValue() {
    UserCreatableMetacardTypes userCreatableMetacardTypes = createAvailableMetacardType("x");
    Map<String, Object> map = createBuildApplicationAndRun(userCreatableMetacardTypes);
    assertThat(map, is(availableTypes("x")));
  }

  @Test
  public void testOneAvailableMetacardTypeWithTwoValues() {
    UserCreatableMetacardTypes userCreatableMetacardTypes = createAvailableMetacardType("x", "y");
    Map<String, Object> map = createBuildApplicationAndRun(userCreatableMetacardTypes);
    assertThat(map, is(availableTypes("x", "y")));
  }

  @Test
  public void testTwoAvailableMetacardTypeWithOneValue() {
    UserCreatableMetacardTypes availableMetacardType1 = createAvailableMetacardType("x");
    UserCreatableMetacardTypes availableMetacardType2 = createAvailableMetacardType("y");
    Map<String, Object> map =
        createBuildApplicationAndRun(availableMetacardType1, availableMetacardType2);
    assertThat(map, is(availableTypes("x", "y")));
  }

  @Test
  public void testTwoAvailableMetacardTypeWithTwoValues() {
    UserCreatableMetacardTypes availableMetacardType1 = createAvailableMetacardType("x", "y");
    UserCreatableMetacardTypes availableMetacardType2 = createAvailableMetacardType("a", "b");
    Map<String, Object> map =
        createBuildApplicationAndRun(availableMetacardType1, availableMetacardType2);
    assertThat(map, is(availableTypes("x", "y", "a", "b")));
  }

  private UserCreatableMetacardTypes createAvailableMetacardType(String... typeNames) {
    UserCreatableMetacardTypes userCreatableMetacardTypes = mock(UserCreatableMetacardTypes.class);
    when(userCreatableMetacardTypes.getAvailableTypes())
        .thenReturn(new HashSet<>(Arrays.asList(typeNames)));
    return userCreatableMetacardTypes;
  }

  private Map<String, Object> createBuildApplicationAndRun(
      UserCreatableMetacardTypes... userCreatableMetacardTypes) {
    BuildApplication buildApplication =
        new BuildApplication(endpointUtil, Arrays.asList(userCreatableMetacardTypes));
    return buildApplication.getAvailableTypesJson();
  }

  private Map<String, Object> availableTypes(String... types) {
    return Collections.singletonMap(
        "availabletypes",
        Arrays.stream(types).map(this::metacardType).collect(Collectors.toList()));
  }

  private Map<String, Object> metacardType(String type) {
    return Collections.singletonMap("metacardType", type);
  }
}
