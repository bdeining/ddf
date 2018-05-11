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
package org.codice.ddf.catalog.ui.metacard.workspace;

import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardType;
import ddf.catalog.data.impl.MetacardImpl;
import java.util.Collections;
import javax.annotation.Nonnull;

public class DeliveryScheduleMetacardImpl extends MetacardImpl {
  public static final MetacardType TYPE = new DeliveryScheduleMetacardTypeImpl();

  public DeliveryScheduleMetacardImpl() {
    super(TYPE);
    setTags(Collections.singleton(DeliveryScheduleMetacardTypeImpl.DELIVERY_TAG));
  }

  public DeliveryScheduleMetacardImpl(@Nonnull Metacard metacard) {
    super(metacard, TYPE);
    setTags(Collections.singleton(DeliveryScheduleMetacardTypeImpl.DELIVERY_TAG));
  }
}
