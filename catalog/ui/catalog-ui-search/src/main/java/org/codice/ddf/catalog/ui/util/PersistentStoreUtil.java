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
package org.codice.ddf.catalog.ui.util;

import ddf.security.Subject;
import ddf.security.SubjectIdentity;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.boon.json.JsonFactory;
import org.codice.ddf.persistence.PersistenceException;
import org.codice.ddf.persistence.PersistentItem;
import org.codice.ddf.persistence.PersistentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersistentStoreUtil {

  private static final Logger LOGGER = LoggerFactory.getLogger(PersistentStoreUtil.class);

  private PersistentStoreUtil() {
    // Should not be instantiated
  }

  public static void setUserPreferences(
      PersistentStore persistentStore, String userId, Map<String, Object> preferences) {
    String json = JsonFactory.create().toJson(preferences);

    LOGGER.trace("preferences JSON text:\n {}", json);
    PersistentItem item = new PersistentItem();
    item.addIdProperty(userId);
    item.addProperty("user", userId);
    item.addProperty(
        "preferences_json",
        "_bin",
        Base64.getEncoder().encodeToString(json.getBytes(Charset.defaultCharset())));

    try {
      persistentStore.add(PersistentStore.PersistenceType.PREFERENCES_TYPE.toString(), item);
    } catch (PersistenceException e) {
      LOGGER.info(
          "PersistenceException while trying to persist preferences for user {}", userId, e);
    }
  }

  public static Map getUserPreferences(PersistentStore persistentStore, String userId) {
    try {
      String filter = String.format("user = '%s'", userId);
      List<Map<String, Object>> preferencesList =
          persistentStore.get(PersistentStore.PersistenceType.PREFERENCES_TYPE.toString(), filter);
      if (preferencesList.size() == 1) {
        byte[] json = (byte[]) preferencesList.get(0).get("preferences_json_bin");

        return JsonFactory.create().parser().parseMap(new String(json, Charset.defaultCharset()));
      }
    } catch (PersistenceException e) {
      LOGGER.info(
          "PersistenceException while trying to retrieve persisted preferences for user {}",
          userId,
          e);
    }

    return Collections.emptyMap();
  }

  public static Map getSubjectPreferences(
      PersistentStore persistentStore, Subject subject, SubjectIdentity subjectIdentity) {
    String userid = subjectIdentity.getUniqueIdentifier(subject);
    return getUserPreferences(persistentStore, userid);
  }

  public static void setSubjectPreferences(
      PersistentStore persistentStore,
      Subject subject,
      SubjectIdentity subjectIdentity,
      Map<String, Object> preferences) {
    String userid = subjectIdentity.getUniqueIdentifier(subject);
    setUserPreferences(persistentStore, userid, preferences);
  }
}
