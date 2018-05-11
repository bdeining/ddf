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

import ddf.catalog.data.AttributeDescriptor;
import ddf.catalog.data.impl.AttributeDescriptorImpl;
import ddf.catalog.data.impl.BasicTypes;
import ddf.catalog.data.impl.MetacardTypeImpl;
import java.util.HashSet;
import java.util.Set;

public class DeliveryScheduleMetacardTypeImpl extends MetacardTypeImpl {
  public static final String DELIVERY_TAG = "delivery";

  public static final String DELIVERY_TYPE_NAME = "metacard.delivery";

  public static final String DELIVERY_SCHEDULE_USER_ID = "userId";

  public static final String IS_SCHEDULED = "isScheduled";

  public static final String DELIVERY_SCHEDULE_INTERVAL = "scheduleInterval";

  public static final String DELIVERY_SCHEDULE_UNIT = "scheduleUnit";

  public static final String DELIVERY_SCHEDULE_START = "scheduleStart";

  public static final String DELIVERY_SCHEDULE_END = "scheduleEnd";

  public static final String SCHEDULE_DELIVERY_IDS = "deliveryIds";

  private static final Set<AttributeDescriptor> DELIVERY_DESCRIPTORS;

  static {
    DELIVERY_DESCRIPTORS = new HashSet<>();

    DELIVERY_DESCRIPTORS.add(
        new AttributeDescriptorImpl(
            DELIVERY_SCHEDULE_USER_ID, false, true, false, false, BasicTypes.STRING_TYPE));

    DELIVERY_DESCRIPTORS.add(
        new AttributeDescriptorImpl(
            IS_SCHEDULED, false, true, false, false, BasicTypes.BOOLEAN_TYPE));

    DELIVERY_DESCRIPTORS.add(
        new AttributeDescriptorImpl(
            DELIVERY_SCHEDULE_INTERVAL, false, true, false, false, BasicTypes.INTEGER_TYPE));

    DELIVERY_DESCRIPTORS.add(
        new AttributeDescriptorImpl(
            DELIVERY_SCHEDULE_UNIT, false, true, false, false, BasicTypes.STRING_TYPE));

    DELIVERY_DESCRIPTORS.add(
        new AttributeDescriptorImpl(
            DELIVERY_SCHEDULE_START, false, true, false, false, BasicTypes.STRING_TYPE));

    DELIVERY_DESCRIPTORS.add(
        new AttributeDescriptorImpl(
            DELIVERY_SCHEDULE_END, false, true, false, false, BasicTypes.STRING_TYPE));

    DELIVERY_DESCRIPTORS.add(
        new AttributeDescriptorImpl(
            SCHEDULE_DELIVERY_IDS, false, true, false, true, BasicTypes.STRING_TYPE));
  }

  public DeliveryScheduleMetacardTypeImpl() {
    this(DELIVERY_TYPE_NAME, DELIVERY_DESCRIPTORS);
  }

  public DeliveryScheduleMetacardTypeImpl(String name, Set<AttributeDescriptor> descriptors) {
    super(name, descriptors);
  }
}
