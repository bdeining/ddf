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
import static ddf.util.Fallible.of;
import static ddf.util.Fallible.ofNullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import ddf.catalog.CatalogFramework;
import ddf.catalog.data.impl.QueryMetacardTypeImpl;
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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.scheduler.SchedulerFuture;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QueryExecutor implements QuerySchedulingPostIngestPlugin.SchedulableFuture {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueryExecutor.class);
  private static final Gson GSON = new GsonBuilder().create();

  private final CatalogFramework catalogFramework;

  private final String queryMetacardID;

  private final String queryMetacardTitle;

  private final Map<String, Object> queryMetacardData;

  private final String scheduleUserID;

  private final int scheduleInterval;

  private final DateTime start;
  private final DateTime end;

  private final IgniteCache<String, Map<String, String>> queryCache;

  private final AtomicInteger unitsPassedSinceStarted;

  @Nullable private SchedulerFuture<?> job = null;

  QueryExecutor(
      CatalogFramework catalogFramework,
      IgniteCache<String, Map<String, String>> queryCache,
      String queryMetacardID,
      String queryMetacardTitle,
      Map<String, Object> queryMetacardData,
      String scheduleUserID,
      int scheduleInterval,
      DateTime start,
      DateTime end) {
    this.catalogFramework = catalogFramework;
    this.queryCache = queryCache;
    this.queryMetacardID = queryMetacardID;
    this.queryMetacardTitle = queryMetacardTitle;
    this.queryMetacardData = queryMetacardData;
    this.scheduleUserID = scheduleUserID;
    this.scheduleInterval = scheduleInterval;
    this.start = start;
    this.end = end;

    // Set this >= scheduleInterval - 1 so that a scheduled query executes the first
    // time it is able.
    unitsPassedSinceStarted = new AtomicInteger(scheduleInterval);
  }

  private Fallible<Map.Entry<DateTime, QueryResponse>> runQuery(
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
                .getOrDefault(QueryMetacardTypeImpl.QUERY_SORTS, "ascending")
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

    DateTime queryTime = DateTime.now();

    return subject.execute(
        () -> {
          try {
            return of(
                new AbstractMap.SimpleEntry<>(queryTime, catalogFramework.query(queryRequest)));
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

  private void storeResult(final Map.Entry<DateTime, QueryResponse> queryResponse) {
    String serializedQueryResult;
    try {
      serializedQueryResult = GSON.toJson(queryResponse.getValue().getResults());
    } catch (JsonIOException exception) {
      LOGGER.info("Error serializing payload {} to JSON {}", queryResponse, exception);
      return;
    }

    ofNullable(
            queryCache.get(queryMetacardID),
            "Query cache for metacard id \"%s\" appears to be null",
            queryMetacardID)
        .ifValue(
            cacheMap -> {
              cacheMap.put(
                  queryResponse.getKey().toDateTimeISO().toString(), serializedQueryResult);

              queryCache.put(queryMetacardID, cacheMap);
            })
        .elseDo(
            error -> {
              LOGGER.debug("No existing query listings found, creating a new one {}", error);

              Map<String, String> queryResultMap = new HashMap<>();
              queryResultMap.put(
                  queryResponse.getKey().toDateTimeISO().toString(), serializedQueryResult);

              queryCache.put(queryMetacardID, queryResultMap);
            });
  }

  private void cancel() {
    unwrapCache()
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
          "Entering QueryExecutor.run(). Acquiring and delivering metacard data for {}...",
          queryMetacardID);

      // Jobs can be cancelled before their end by removing their jobID from the list of jobIDs
      // associated with the queryMetacardID key.
      final boolean isCancelled =
          unwrapCache()
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

      if (start.compareTo(now) > 0) {
        return;
      }

      synchronized (unitsPassedSinceStarted) {
        if (unitsPassedSinceStarted.get() < scheduleInterval - 1) {
          unitsPassedSinceStarted.incrementAndGet();
          return;
        }
      }

      unitsPassedSinceStarted.set(0);

      LOGGER.debug(
          "Running query for query metacard with id: {} and title: {}...",
          queryMetacardID,
          queryMetacardTitle);

      runQuery(getSecurity().getGuestSubject("0.0.0.0"), queryMetacardData)
          .ifValue(this::storeResult);

      if (job.nextExecutionTime() == 0
          || end.compareTo(new DateTime(job.nextExecutionTime(), DateTimeZone.UTC)) <= 0) {
        cancel();
      }
    } catch (Exception exception) {
      LOGGER.error(
          String.format(
              "An error occurred while trying to query results for user '%s': ", scheduleUserID),
          exception);
    }
  }

  // This method is intended to be called immediately after the creation of the job that will run
  // an instance of this class with the SchedulerFuture returned from the IgniteScheduler.
  // This setter must exist in order to allow the ID of the job running this Runnable to be
  // retrieved here for the future cannot be retrieved until the job is created and the job
  // cannot be created without first creating this, creating a circular data dependency.
  public void setJob(@Nonnull SchedulerFuture<?> job) {
    this.job = job;
  }
}
