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

import static spark.Spark.get;

import com.google.common.annotations.VisibleForTesting;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.codice.ddf.catalog.ui.util.EndpointUtil;
import spark.servlet.SparkApplication;

public class BuildApplication implements SparkApplication {

  private final EndpointUtil endpointUtil;

  private final List<UserCreatableMetacardTypes> userCreatableMetacardTypesList;

  public BuildApplication(
      EndpointUtil endpointUtil, List<UserCreatableMetacardTypes> userCreatableMetacardTypesList) {
    this.endpointUtil = endpointUtil;
    this.userCreatableMetacardTypesList = userCreatableMetacardTypesList;
  }

  @Override
  public void init() {
    /** Get the available types that were explicitly configured. */
    get(
        "/builder/availabletypes",
        (request, response) -> getAvailableTypesJson(),
        endpointUtil::getJson);
  }

  @VisibleForTesting
  Map<String, Object> getAvailableTypesJson() {
    List<Map<String, String>> availableTypes =
        this.userCreatableMetacardTypesList
            .stream()
            .map(UserCreatableMetacardTypes::getAvailableTypes)
            .flatMap(Collection::stream)
            .distinct()
            .map(s -> Collections.singletonMap("metacardType", s))
            .collect(Collectors.toList());

    return Collections.singletonMap("availabletypes", availableTypes);
  }
}
