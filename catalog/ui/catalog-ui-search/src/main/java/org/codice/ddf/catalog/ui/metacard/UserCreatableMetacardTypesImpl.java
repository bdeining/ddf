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

import ddf.catalog.data.MetacardType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Given a list of {@link MetacardType}s, return a set of the type names. */
public class UserCreatableMetacardTypesImpl implements UserCreatableMetacardTypes {

  private final List<MetacardType> metacardTypeList;

  public UserCreatableMetacardTypesImpl(List<MetacardType> metacardTypeList) {
    this.metacardTypeList = metacardTypeList;
  }

  @Override
  public Set<String> getAvailableTypes() {
    return metacardTypeList.stream().map(MetacardType::getName).collect(Collectors.toSet());
  }
}
