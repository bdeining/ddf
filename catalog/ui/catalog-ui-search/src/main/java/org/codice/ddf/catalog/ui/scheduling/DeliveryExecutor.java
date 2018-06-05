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
package org.codice.ddf.catalog.ui.scheduling;

import static ddf.util.Fallible.error;
import static ddf.util.Fallible.forEach;
import static ddf.util.Fallible.of;
import static ddf.util.Fallible.ofNullable;
import static ddf.util.Fallible.success;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.Result;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.data.impl.ResultImpl;
import ddf.util.Fallible;
import ddf.util.MapUtils;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.scheduler.SchedulerFuture;
import org.boon.json.JsonException;
import org.boon.json.JsonFactory;
import org.codice.ddf.catalog.ui.scheduling.serialization.GenericJsonTypeAdapter;
import org.codice.ddf.catalog.ui.scheduling.subscribers.QueryCourier;
import org.codice.ddf.persistence.PersistenceException;
import org.codice.ddf.persistence.PersistentStore;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DeliveryExecutor implements QuerySchedulingPostIngestPlugin.SchedulableFuture {
  private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryExecutor.class);

  private final Gson gson;

  private final String workspaceMetacardId;
  private final String queryMetacardId;
  private final String queryMetacardTitle;
  private final Collection<String> scheduleDeliveryIDs;
  private final String scheduleUserID;
  private final Map<String, Object> scheduleData;

  private final boolean delayed;
  private final int hours;
  private final int minutes;

  private final PersistentStore persistentStore;
  private final Fallible<List<QueryCourier>> serviceReferences;
  private final IgniteCache<String, Map<String, String>> queryCache;

  private static final DateTimeFormatter ISO_8601_DATE_FORMAT =
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZoneUTC();

  private final Integer queryScheduledAmount;
  private DateTime queryStartTime;
  private DateTime queryEndTime;

  private final AtomicInteger unitsPassedSinceStarted;

  @Nullable private SchedulerFuture<?> job = null;

  DeliveryExecutor(
      PersistentStore persistentStore,
      Fallible<List<QueryCourier>> serviceReferences,
      IgniteCache<String, Map<String, String>> queryCache,
      String workspaceMetacardId,
      String queryMetacardId,
      String queryMetacardTitle,
      Collection<String> scheduleDeliveryIDs,
      String scheduleUserID,
      boolean delayed,
      int hours,
      int minutes,
      Map<String, Object> scheduleData) {
    this.persistentStore = persistentStore;
    this.serviceReferences = serviceReferences;
    this.queryCache = queryCache;
    this.workspaceMetacardId = workspaceMetacardId;
    this.queryMetacardId = queryMetacardId;
    this.queryMetacardTitle = queryMetacardTitle;
    this.scheduleDeliveryIDs = scheduleDeliveryIDs;
    this.scheduleUserID = scheduleUserID;
    this.hours = hours;
    this.minutes = minutes;
    this.delayed = delayed;
    this.scheduleData = scheduleData;

    queryScheduledAmount = (Integer) scheduleData.get(ScheduleMetacardTypeImpl.SCHEDULE_AMOUNT);
    String startString = (String) scheduleData.get(ScheduleMetacardTypeImpl.SCHEDULE_START);
    String endString = (String) scheduleData.get(ScheduleMetacardTypeImpl.SCHEDULE_END);

    try {
      queryStartTime = DateTime.parse(startString, ISO_8601_DATE_FORMAT);
    } catch (DateTimeParseException | IllegalArgumentException exception) {
      queryStartTime = new DateTime();
    }
    try {
      queryEndTime = DateTime.parse(endString, ISO_8601_DATE_FORMAT);
    } catch (DateTimeParseException | IllegalArgumentException exception) {
      queryEndTime = new DateTime().plusYears(1);
    }

    unitsPassedSinceStarted = new AtomicInteger(queryScheduledAmount);

    this.gson =
        new GsonBuilder()
            .registerTypeAdapter(
                Result.class, new GenericJsonTypeAdapter<Result, ResultImpl>(ResultImpl.class))
            .registerTypeAdapter(
                Metacard.class,
                new GenericJsonTypeAdapter<Metacard, MetacardImpl>(MetacardImpl.class))
            .create();
  }

  private Fallible<Map<String, Object>> getUserPreferences(final String userID) {
    List<Map<String, Object>> preferencesList;
    try {
      preferencesList =
          persistentStore.get(
              PersistentStore.PersistenceType.PREFERENCES_TYPE.toString(),
              String.format("user = '%s'", userID));
    } catch (PersistenceException exception) {
      return error(
          "There was a problem attempting to retrieve the preferences for user '%s': %s",
          userID, exception.getMessage());
    }
    if (preferencesList.size() != 1) {
      return error(
          "There were %d preference entries found for user '%s'!", preferencesList.size(), userID);
    }
    final Map<String, Object> preferencesItem = preferencesList.get(0);

    return MapUtils.tryGet(preferencesItem, "preferences_json_bin", byte[].class)
        .tryMap(
            json -> {
              try {
                return of(
                    JsonFactory.create()
                        .parser()
                        .parseMap(new String(json, Charset.defaultCharset())));
              } catch (JsonException exception) {
                return error(
                    "There was an error parsing the preferences for user '%s': %s",
                    userID, exception.getMessage());
              }
            });
  }

  private Fallible<Pair<String, Map<String, Object>>> getDeliveryInfo(
      final Map<String, Object> userPreferences, final String deliveryId) {
    return MapUtils.tryGet(
            userPreferences, QuerySchedulingPostIngestPlugin.DELIVERY_METHODS_KEY, List.class)
        .tryMap(
            userDeliveryMethods -> {
              final List<Map<String, Object>> matchingDestinations =
                  ((List<Map<String, Object>>) userDeliveryMethods)
                      .stream()
                      .filter(
                          destination ->
                              MapUtils.tryGet(
                                      destination,
                                      QuerySchedulingPostIngestPlugin.DELIVERY_METHOD_ID_KEY,
                                      String.class)
                                  .map(deliveryId::equals)
                                  .orDo(
                                      error -> {
                                        LOGGER.error(
                                            String.format(
                                                "There was a problem attempting to retrieve the ID for a destination in the given preferences: %s",
                                                error));
                                        return false;
                                      }))
                      .collect(Collectors.toList());
              if (matchingDestinations.size() != 1) {
                return error(
                    "There were %d destinations matching the ID \"%s\" in the given preferences; 1 is expected!",
                    matchingDestinations.size(), deliveryId);
              }
              final Map<String, Object> destinationData = matchingDestinations.get(0);

              return MapUtils.tryGetAndRun(
                  destinationData,
                  QueryCourier.DELIVERY_TYPE_KEY,
                  String.class,
                  QuerySchedulingPostIngestPlugin.DELIVERY_PARAMETERS_KEY,
                  Map.class,
                  (deliveryType, deliveryOptions) ->
                      of(ImmutablePair.of(deliveryType, (Map<String, Object>) deliveryOptions)));
            });
  }

  private Fallible<?> deliver(
      final String deliveryType,
      final String queryMetacardTitle,
      final Fallible<List<Result>> payload,
      final String userID,
      final String deliveryID,
      final Map<String, Object> deliveryParameters) {
    LOGGER.trace("Delivering records for {} to {}", queryMetacardTitle, deliveryType);
    if (payload.hasError())
      return payload.prependToError("Delivery payload contains errors, will not be delivered: ");

    List<Result> results = payload.or(Arrays.asList());

    final List<QueryCourier> selectedServices =
        serviceReferences
            .map(
                references ->
                    references
                        .stream()
                        .filter(service -> deliveryType.equals(service.getDeliveryType()))
                        .collect(Collectors.toList()))
            .or(Arrays.asList());

    if (selectedServices.isEmpty()) {
      return error("The delivery method \"%s\" was not recognized", deliveryType);
    } else if (selectedServices.size() > 1) {
      final String selectedServicesString =
          selectedServices
              .stream()
              .map(selectedService -> selectedService.getClass().getCanonicalName())
              .collect(Collectors.joining(", "));
      return error(
          "%d delivery services were found to handle the delivery type %s: %s.",
          selectedServices.size(), deliveryType, selectedServicesString);
    }

    final Function<String, String> prependContext =
        message ->
            String.format(
                "There was a problem delivering query results to delivery info with ID \"%s\" for user '%s': %s",
                deliveryID, scheduleUserID, message);
    selectedServices
        .get(0)
        .deliver(
            workspaceMetacardId,
            queryMetacardId,
            queryMetacardTitle,
            results,
            userID,
            deliveryID,
            deliveryParameters,
            error -> LOGGER.error(prependContext.apply(error)),
            () ->
                LOGGER.trace(
                    String.format(
                        "Query results were delivered to delivery info with ID \"%s\" for user '%s'.",
                        deliveryID, scheduleUserID)),
            warning -> LOGGER.warn(prependContext.apply(warning)));

    return success();
  }

  private void deliverQueryResults() {
    ofNullable(
            queryCache.get(queryMetacardId),
            "Query result cache does not appear to exist, no delivery will be attempted")
        .ifValue(
            resultMap ->
                resultMap
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getValue() != null && entry.getKey() != null)
                    .forEach(
                        entry ->
                            processDelivery(entry)
                                .ifValue(value -> removeResultCacheEntry(entry.getKey()))
                                .elseDo(LOGGER::info)))
        .elseDo(LOGGER::info);
  }

  private List<Result> readJson(String json) {
    List<Result> results = new ArrayList<>();
    JsonElement element = new JsonParser().parse(json);
    if (element.isJsonArray()) {
      for (JsonElement ele : element.getAsJsonArray()) {
        JsonObject r = ele.getAsJsonObject();
        ResultImpl result = new ResultImpl();
        if (r.get("distance") != null) {
          result.setDistanceInMeters(r.get("distance").getAsDouble());
        }
        if (r.get("relevanceScore") != null) {
          result.setRelevanceScore(r.get("relevanceScore").getAsDouble());
        }
        MetacardImpl metacard = new MetacardImpl();
        JsonObject m = r.get("metacard").getAsJsonObject();
        for (String key : m.keySet()) {
          JsonElement value = m.get(key);
          if (value.isJsonArray()) {
            List<Serializable> values = new ArrayList<>();
            for (JsonElement i : value.getAsJsonArray()) {
              values.add(i.getAsString());
            }
            metacard.setAttribute(new AttributeImpl(key, values));
          } else {
            metacard.setAttribute(key, value.getAsString());
          }
        }
        result.setMetacard(metacard);
        results.add(result);
      }
    }
    return results;
  }

  private Fallible<?> processDelivery(final Map.Entry<String, String> entry) {
    final Fallible<List<Result>> entryValue =
        of(entry.getValue())
            .tryMap(
                value -> {
                  try {
                    // Type type = new TypeToken<List<Result>>() {}.getType();
                    // return of(gson.fromJson(value, type));
                    return of(readJson(value));
                  } catch (Exception exception) {
                    return error("Error de-serializing cache entry: %s", exception);
                  }
                });

    return getUserPreferences(scheduleUserID)
        .tryMap(
            userPreferences ->
                forEach(
                    scheduleDeliveryIDs,
                    deliveryId ->
                        getDeliveryInfo(userPreferences, deliveryId)
                            .prependToError(
                                "There was a problem retrieving the delivery information with ID \"%s\" for user '%s': ",
                                deliveryId, scheduleUserID)
                            .tryMap(
                                deliveryInfo ->
                                    deliver(
                                            deliveryInfo.getLeft(),
                                            queryMetacardTitle,
                                            entryValue,
                                            scheduleUserID,
                                            deliveryId,
                                            deliveryInfo.getRight())
                                        .prependToError(
                                            "There was a problem delivering query results to delivery info with ID \"%s\" for user '%s': ",
                                            deliveryId, scheduleUserID))));
  }

  private Fallible removeResultCacheEntry(final String resultCacheKey) {
    return ofNullable(
            queryCache.get(queryMetacardId),
            "Query result cache for metacard id \"%s\" appears to be null",
            queryMetacardId)
        .ifValue(
            cacheMap -> {
              cacheMap.remove(resultCacheKey);
              queryCache.put(queryMetacardId, cacheMap);
            })
        .elseDo(
            error ->
                LOGGER.info(
                    "Query result cache for metacard id {} has no entry for {} : {}",
                    queryMetacardId,
                    resultCacheKey,
                    error));
  }

  private void cancel() {
    unwrapCache()
        .ifValue(
            deliveries -> {
              if (deliveries.containsKey(queryMetacardId)
                  && (job == null || deliveries.get(queryMetacardId).equals(job.id()))) {
                deliveries.remove(queryMetacardId);
              }
            })
        .elseDo(
            error ->
                LOGGER.warn(
                    "When cancelling a completed delivery scheduled job \"%s\" for metacard \"%s\", the delivery cache could not be found to remove the job",
                    job == null ? "null" : job.id(), queryMetacardId));

    if (job != null) {
      job.cancel();
    }
  }

  @Override
  public void run() {
    try {
      LOGGER.debug(
          "Entering {}.run(). Delivering metacard data for {}...",
          DeliveryExecutor.class.getName(),
          queryMetacardId);

      final boolean isCanceled =
          unwrapCache()
              .map(
                  deliveries ->
                      !deliveries.containsKey(queryMetacardId)
                          || job != null && !deliveries.get(queryMetacardId).contains(job.id()))
              .orDo(
                  error -> {
                    LOGGER.warn(
                        String.format(
                            "Delivery data could not be found when the delivery via metacard \"%s\" ran:\nThis scheduled delivery might not have been canceled by the user: %s",
                            queryMetacardId, error));

                    return false;
                  });

      final DateTime now = DateTime.now();
      if (job != null && (isCanceled || queryEndTime.compareTo(now) < 0)) {
        cancel();
        return;
      }

      if (queryStartTime.compareTo(now) > 0) {
        return;
      }

      if (!this.delayed) {
        synchronized (unitsPassedSinceStarted) {
          if (unitsPassedSinceStarted.get() < queryScheduledAmount - 1) {
            unitsPassedSinceStarted.incrementAndGet();
            return;
          }
        }

        unitsPassedSinceStarted.set(0);
      }

      if (job != null && isCanceled) {
        cancel();
        return;
      }

      LOGGER.debug(
          "Running delivery for metacard with id: {} and title {}...",
          queryMetacardId,
          queryMetacardTitle);

      deliverQueryResults();

    } catch (Exception exception) {
      LOGGER.error(
          String.format(
              "An error occurred trying to deliver query results using the following scheduled delivery configurations [%s] for user '%s': ",
              String.join(", ", scheduleDeliveryIDs), scheduleUserID),
          exception);
    }
  }

  public void setJob(@Nonnull SchedulerFuture<?> job) {
    this.job = job;
  }
}
