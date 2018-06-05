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
package org.codice.ddf.catalog.ui.security;

import static org.boon.HTTP.APPLICATION_JSON;
import static spark.Spark.exception;
import static spark.Spark.get;
import static spark.Spark.put;

import com.google.common.collect.ImmutableMap;
import ddf.security.Subject;
import ddf.security.SubjectIdentity;
import ddf.security.SubjectUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.boon.json.JsonFactory;
import org.codice.ddf.catalog.ui.metacard.EntityTooLargeException;
import org.codice.ddf.catalog.ui.util.EndpointUtil;
import org.codice.ddf.catalog.ui.util.PersistentStoreUtil;
import org.codice.ddf.persistence.PersistentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.servlet.SparkApplication;

public class UserApplication implements SparkApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(UserApplication.class);

  private final EndpointUtil endpointUtil;

  private final PersistentStore persistentStore;

  private final SubjectIdentity subjectIdentity;

  public UserApplication(
      EndpointUtil util, PersistentStore persistentStore, SubjectIdentity subjectIdentity) {
    this.endpointUtil = util;
    this.persistentStore = persistentStore;
    this.subjectIdentity = subjectIdentity;
  }

  @Override
  public void init() {
    get(
        "/user",
        (req, res) -> {
          Subject subject = (Subject) SecurityUtils.getSubject();
          res.type(APPLICATION_JSON);
          return getSubjectAttributes(subject);
        },
        endpointUtil::getJson);

    put(
        "/user/preferences",
        APPLICATION_JSON,
        (req, res) -> {
          Subject subject = (Subject) SecurityUtils.getSubject();

          if (subject.isGuest()) {
            res.status(401);
            return ImmutableMap.of("message", "Guest cannot save preferences.");
          }

          Map<String, Object> preferences =
              JsonFactory.create().parser().parseMap(endpointUtil.safeGetBody(req));

          if (preferences == null) {
            preferences = new HashMap<>();
          }

          handleServerGeneratedAlerts(
              preferences, (Map<String, Object>) getSubjectAttributes(subject).get("preferences"));
          PersistentStoreUtil.setSubjectPreferences(
              persistentStore, subject, subjectIdentity, preferences);

          return preferences;
        },
        endpointUtil::getJson);

    exception(EntityTooLargeException.class, endpointUtil::handleEntityTooLargeException);

    exception(IOException.class, endpointUtil::handleIOException);

    exception(RuntimeException.class, endpointUtil::handleRuntimeException);
  }

  private void handleServerGeneratedAlerts(Map newPreferences, Map oldPreferences) {
    final List<Map<String, Object>> newAlerts;
    if (CollectionUtils.isEmpty((List<Map<String, Object>>) newPreferences.get("alerts"))) {
      newAlerts = new ArrayList<>();
    } else {
      newAlerts = (List<Map<String, Object>>) newPreferences.get("alerts");
    }

    List<Map<String, Object>> oldAlerts = (List<Map<String, Object>>) oldPreferences.get("alerts");
    if (oldAlerts == null) {
      oldAlerts = new ArrayList<>();
    }

    List<Map<String, Object>> alertsToPreserve =
        oldAlerts
            .stream()
            .filter(
                (alert) ->
                    newAlerts
                        .stream()
                        .noneMatch((oldAlert) -> oldAlert.get("id").equals(alert.get("id"))))
            .filter((alert) -> (Boolean) alert.getOrDefault("serverGenerated", false))
            .collect(Collectors.toList());

    newAlerts.addAll(alertsToPreserve);
    newPreferences.put("alerts", newAlerts);
  }

  private Set<String> getSubjectRoles(Subject subject) {
    return new TreeSet<>(SubjectUtils.getAttribute(subject, Constants.ROLES_CLAIM_URI));
  }

  private Map<String, Object> getSubjectAttributes(Subject subject) {
    // @formatter:off
    Map<String, Object> required =
        ImmutableMap.of(
            "userid", subjectIdentity.getUniqueIdentifier(subject),
            "username", SubjectUtils.getName(subject),
            "isGuest", subject.isGuest(),
            "roles", getSubjectRoles(subject),
            "preferences",
                PersistentStoreUtil.getSubjectPreferences(
                    persistentStore, subject, subjectIdentity));
    // @formatter:on

    String email = SubjectUtils.getEmailAddress(subject);

    if (StringUtils.isEmpty(email)) {
      return required;
    }

    return ImmutableMap.<String, Object>builder().putAll(required).put("email", email).build();
  }
}
