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
import static ddf.util.Fallible.success;

import ddf.catalog.federation.FederationException;
import ddf.catalog.operation.Query;
import ddf.catalog.operation.QueryRequest;
import ddf.catalog.operation.QueryResponse;
import ddf.catalog.operation.impl.QueryImpl;
import ddf.catalog.operation.impl.QueryRequestImpl;
import ddf.catalog.source.SourceUnavailableException;
import ddf.catalog.source.UnsupportedQueryException;
import ddf.security.Subject;
import ddf.util.Fallible;
import ddf.util.MapUtils;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.ignite.scheduler.SchedulerFuture;
import org.boon.json.JsonException;
import org.boon.json.JsonFactory;
import org.codice.ddf.catalog.ui.metacard.workspace.QueryMetacardTypeImpl;
import org.codice.ddf.catalog.ui.scheduling.subscribers.QueryCourier;
import org.codice.ddf.persistence.PersistenceException;
import org.codice.ddf.persistence.PersistentStore.PersistenceType;
import org.codice.ddf.security.common.Security;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;
import org.osgi.framework.InvalidSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class QueryDeliveryExecutor implements Runnable {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueryDeliveryExecutor.class);

  public static final int DEFAULT_PAGE_SIZE = 100;

  private static final Security SECURITY = Security.getInstance();

  private final QuerySchedulingPostIngestPlugin plugin;

  private final String queryMetacardID;

  private final String queryMetacardTitle;

  private final Map<String, Object> queryMetacardData;

  private final Collection<String> scheduleDeliveryIDs;

  private final String scheduleUserID;

  private final int scheduleInterval;

  private final DateTime start;

  private final DateTime end;

  private int unitsPassedSinceStarted;

  private @Nullable SchedulerFuture<?> job = null;

  QueryDeliveryExecutor(
      QuerySchedulingPostIngestPlugin plugin,
      String queryMetacardID,
      String queryMetacardTitle,
      Map<String, Object> queryMetacardData,
      Collection<String> scheduleDeliveryIDs,
      String scheduleUserID,
      int scheduleInterval,
      DateTime start,
      DateTime end) {
    this.plugin = plugin;
    this.queryMetacardID = queryMetacardID;
    this.queryMetacardTitle = queryMetacardTitle;
    this.queryMetacardData = queryMetacardData;
    this.scheduleDeliveryIDs = scheduleDeliveryIDs;
    this.scheduleUserID = scheduleUserID;
    this.scheduleInterval = scheduleInterval;
    this.start = start;
    this.end = end;

    // Set this >= scheduleInterval - 1 so that a scheduled query executes the first
    // time it is able.
    unitsPassedSinceStarted = scheduleInterval;
  }

  private Fallible<QueryResponse> runQuery(
      final Subject subject, final Map<String, Object> queryMetacardData) {

    final Filter filter;
    final String cqlQuery =
        (String) queryMetacardData.getOrDefault(QueryMetacardTypeImpl.QUERY_CQL, "");
    try {
      filter = ECQL.toFilter(cqlQuery);
    } catch (CQLException exception) {
      return error(
          "There was a problem reading the given query expression: " + exception.getMessage());
    }

    final SortBy sortBy =
        queryMetacardData
                .getOrDefault(QueryMetacardTypeImpl.QUERY_SORT_ORDER, "ascending")
                .equals("ascending")
            ? SortBy.NATURAL_ORDER
            : SortBy.REVERSE_ORDER;
    final List<String> sources =
        (List<String>) queryMetacardData.getOrDefault("src", new ArrayList<>());

    // TODO Swap this out with the one defined in the Metatype for Catalog UI Search somehow
    final int pageSize = 250;

    LOGGER.trace(
        "Performing scheduled query. CqlQuery: {}, SortBy: {}, Sources: {}, Page Size: {}",
        cqlQuery,
        sortBy,
        sources,
        pageSize);

    final Query query =
        new QueryImpl(
            filter, 1, pageSize, sortBy, true, QuerySchedulingPostIngestPlugin.QUERY_TIMEOUT_MS);
    final QueryRequest queryRequest;
    if (sources.isEmpty()) {
      LOGGER.trace("Performing enterprise query...");
      queryRequest = new QueryRequestImpl(query, true);
    } else {
      LOGGER.trace("Performing query on specified sources: {}", sources);
      queryRequest = new QueryRequestImpl(query, sources);
    }

    return subject.execute(
        () -> {
          try {
            return of(plugin.getCatalogFramework().query(queryRequest));
          } catch (UnsupportedQueryException exception) {
            return error(
                "The query \"%s\" is not supported by the given catalog framework: %s",
                cqlQuery, exception.getMessage());
          } catch (SourceUnavailableException exception) {
            return error(
                "The catalog framework sources were unavailable: %s", exception.getMessage());
          } catch (FederationException exception) {
            return error(
                "There was a problem with executing a federated search for the query \"%s\": %s",
                cqlQuery, exception.getMessage());
          }
        });
  }

  private Fallible<?> deliver(
      final String deliveryType,
      final String queryMetacardTitle,
      final QueryResponse results,
      final String userID,
      final String deliveryID,
      final Map<String, Object> deliveryParameters) {
    final String filter = String.format("(objectClass=%s)", QueryCourier.class.getName());

    final Stream<QueryCourier> deliveryServices;
    try {
      deliveryServices =
          plugin
              .getBundleContext()
              .getServiceReferences(QueryCourier.class, filter)
              .stream()
              .map(plugin.getBundleContext()::getService)
              .filter(Objects::nonNull);
    } catch (InvalidSyntaxException exception) {
      return error(
          String.format(
              "The filter used to search for query delivery services, \"%s\", was invalid: %s",
              filter, exception.getMessage()));
    }

    final List<QueryCourier> selectedServices =
        deliveryServices
            .filter(deliveryService -> deliveryService.getDeliveryType().equals(deliveryType))
            .collect(Collectors.toList());

    if (selectedServices.isEmpty()) {
      return error(
          String.format(
              "The delivery method \"%s\" was not recognized; this query scheduling system found the following delivery methods: %s.",
              deliveryType,
              deliveryServices.map(QueryCourier::getDeliveryType).collect(Collectors.toList())));
    } else if (selectedServices.size() > 1) {
      final String selectedServicesString =
          selectedServices
              .stream()
              .map(selectedService -> selectedService.getClass().getCanonicalName())
              .collect(Collectors.joining(", "));
      return error(
          String.format(
              "%d delivery services were found to handle the delivery type %s: %s.",
              selectedServices.size(), deliveryType, selectedServicesString));
    }

    final Function<String, String> prependContext =
        message ->
            String.format(
                "There was a problem delivering query results to delivery info with ID \"%s\" for user '%s': %s",
                deliveryID, scheduleUserID, message);
    selectedServices
        .get(0)
        .deliver(
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

  private Fallible<Map<String, Object>> getUserPreferences(final String userID) {
    List<Map<String, Object>> preferencesList;
    try {
      preferencesList =
          plugin
              .getPersistentStore()
              .get(
                  PersistenceType.PREFERENCES_TYPE.toString(),
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
              final Map<String, Object> preferences;
              try {
                preferences =
                    JsonFactory.create()
                        .parser()
                        .parseMap(new String(json, Charset.defaultCharset()));
              } catch (JsonException exception) {
                return error(
                    "There was an error parsing the preferences for user '%s': %s",
                    userID, exception.getMessage());
              }

              return of(preferences);
            });
  }

  private Fallible<Pair<String, Map<String, Object>>> getDeliveryInfo(
      final Map<String, Object> userPreferences, final String deliveryID) {
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
                                  .map(deliveryID::equals)
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
                    matchingDestinations.size(), deliveryID);
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

  private void deliverAll(
      final Collection<String> scheduleDeliveryIDs,
      final String scheduleUserID,
      final String queryMetacardTitle,
      final QueryResponse results) {
    getUserPreferences(scheduleUserID)
        .tryMap(
            userPreferences ->
                forEach(
                    scheduleDeliveryIDs,
                    deliveryID ->
                        getDeliveryInfo(userPreferences, deliveryID)
                            .prependToError(
                                "There was a problem retrieving the delivery information with ID \"%s\" for user '%s': ",
                                deliveryID, scheduleUserID)
                            .tryMap(
                                deliveryInfo ->
                                    deliver(
                                            deliveryInfo.getLeft(),
                                            queryMetacardTitle,
                                            results,
                                            scheduleUserID,
                                            deliveryID,
                                            deliveryInfo.getRight())
                                        .prependToError(
                                            "There was a problem delivering query results to delivery info with ID \"%s\" for user '%s': ",
                                            deliveryID, scheduleUserID))))
        .elseDo(LOGGER::error);
  }

  private void cancel() {
    QuerySchedulingPostIngestPlugin.getRunningQueriesCache(false)
        .ifValue(
            runningQueries -> {
              if (runningQueries.containsKey(queryMetacardID)
                  && (job == null || runningQueries.get(queryMetacardID).equals(job.id()))) {
                runningQueries.remove(queryMetacardID);
              }
            })
        .elseDo(
            error ->
                LOGGER.warn(
                    String.format(
                        "While cancelling a completed query scheduling job \"%s\" for query metacard \"%s\", the running queries cache could not be found to remove the job!",
                        job == null ? "null" : job.id(), queryMetacardID)));
    if (job != null) {
      job.cancel();
    }
  }

  @Override
  public void run() {
    try {
      LOGGER.debug(
          "Entering QueryDeliveryExecutor.run(). Acquiring and delivering metacard data for {}...",
          queryMetacardID);

      // Jobs can be cancelled before their end by removing their jobID from the list of jobIDs
      // associated with the queryMetacardID key.
      final boolean isCancelled =
          QuerySchedulingPostIngestPlugin.getRunningQueriesCache(false)
              .map(
                  runningQueries ->
                      !runningQueries.containsKey(queryMetacardID)
                          // If jobID is null, then the job is being run very immediately after
                          // creation, so assume that it is not cancelled.
                          || job != null && !runningQueries.get(queryMetacardID).contains(job.id()))
              .orDo(
                  error -> {
                    LOGGER.warn(
                        String.format(
                            "Running query data could not be found when the query via metacard \"%s\" ran: %s\nForcing assumption that this scheduled query was not cancelled by the user...",
                            queryMetacardID, error));
                    return false;
                  });
      final DateTime now = DateTime.now();
      if (job != null && (isCancelled || end.compareTo(now) < 0)) {
        cancel();
        return;
      }

      if (start.compareTo(now) <= 0) {
        if (unitsPassedSinceStarted < scheduleInterval - 1) {
          unitsPassedSinceStarted++;
          return;
        }

        unitsPassedSinceStarted = 0;

        LOGGER.debug(
            "Running query for query metacard with id: {} and title: {}...",
            queryMetacardID,
            queryMetacardTitle);

        runQuery(SECURITY.getGuestSubject("0.0.0.0"), queryMetacardData)
            .ifValue(
                results ->
                    deliverAll(scheduleDeliveryIDs, scheduleUserID, queryMetacardTitle, results));

        if (job.nextExecutionTime() == 0
            || end.compareTo(new DateTime(job.nextExecutionTime(), DateTimeZone.UTC)) <= 0) {
          cancel();
        }
      }
    } catch (Exception exception) {
      LOGGER.error(
          String.format(
              "An error occurred while trying to deliver scheduled query results for delivery configurations [%s] for user '%s': ",
              String.join(", ", scheduleDeliveryIDs), scheduleUserID),
          exception);
    }
  }

  // This method is intended to be called immediately after the creation of the job that will run
  // an instance of this class with the SchedulerFuture returned from the IgniteScheduler.
  // This setter must exist in order to allow the ID of the job running this Runnable to be
  // retrieved here for the future cannot be retrieved until the job is created and the job
  // cannot be created without first creating this, creating a circular data dependency.
  void setJob(@Nonnull SchedulerFuture<?> job) {
    this.job = job;
  }
}
