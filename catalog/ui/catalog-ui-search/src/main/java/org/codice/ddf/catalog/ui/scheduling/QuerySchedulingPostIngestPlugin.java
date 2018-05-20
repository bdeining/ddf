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

import ddf.catalog.CatalogFramework;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.QueryMetacardTypeImpl;
import ddf.catalog.operation.CreateResponse;
import ddf.catalog.operation.DeleteResponse;
import ddf.catalog.operation.UpdateResponse;
import ddf.catalog.plugin.PluginExecutionException;
import ddf.catalog.plugin.PostIngestPlugin;
import ddf.util.Fallible;
import ddf.util.MapUtils;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.cache.CacheException;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteScheduler;
import org.apache.ignite.IgniteState;
import org.apache.ignite.Ignition;
import org.apache.ignite.scheduler.SchedulerFuture;
import org.apache.ignite.transactions.TransactionException;
import org.codice.ddf.catalog.ui.metacard.workspace.DeliveryScheduleMetacardTypeImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceAttributes;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceMetacardImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceTransformer;
import org.codice.ddf.catalog.ui.scheduling.subscribers.QueryCourier;
import org.codice.ddf.persistence.PersistentStore;
import org.codice.ddf.security.common.Security;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuerySchedulingPostIngestPlugin implements PostIngestPlugin {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(QuerySchedulingPostIngestPlugin.class);

  enum ExecutorType {
    QUERY {
      Supplier<Fallible<IgniteCache<String, Set<String>>>> getCache() {
        return () -> getRunningQueriesCache(false);
      }
    },
    DELIVERY {
      Supplier<Fallible<IgniteCache<String, Set<String>>>> getCache() {
        return () -> getDeliveryScheduleCache(false);
      }
    };

    static <T extends SchedulableFuture> Fallible<ExecutorType> fromSchedulableFuture(
        Class<T> classType) {
      if (classType.isAssignableFrom(DeliveryExecutor.class)) return of(DELIVERY);
      else if (classType.isAssignableFrom(QueryExecutor.class)) return of(QUERY);

      return error("No valid enum found for %s to retrieve a cache", classType);
    }

    abstract Supplier<Fallible<IgniteCache<String, Set<String>>>> getCache();
  }

  interface SchedulableFuture extends Runnable {
    void setJob(@Nonnull SchedulerFuture<?> job);

    default Security getSecurity() {
      return Security.getInstance();
    }

    @Nullable
    default Supplier<Fallible<IgniteCache<String, Set<String>>>> getCache() {
      return ExecutorType.fromSchedulableFuture(getClass())
          .orDo(
              error -> {
                LOGGER.error("Error retrieving executor cache {}", error);
                return null;
              })
          .getCache();
    }

    default Fallible<IgniteCache<String, Set<String>>> unwrapCache() {
      return Optional.ofNullable(getCache().get())
          .orElse(error("No running cache available for %s", getClass().getName()));
    }
  }

  @FunctionalInterface
  interface PartialJob {
    SchedulableFuture apply(DateTime start, DateTime end, Integer interval, IgniteCache cache);
  }

  @FunctionalInterface
  interface PartialDelivery {
    SchedulableFuture apply(
        Boolean delayed,
        Integer hours,
        Integer minutes,
        Map<String, Object> scheduleData,
        IgniteCache cache);
  }

  public static final String DELIVERY_METHODS_KEY = "deliveryMethods";

  public static final String DELIVERY_METHOD_ID_KEY = "deliveryId";

  public static final String DELIVERY_PARAMETERS_KEY = "deliveryParameters";

  public static final String QUERIES_CACHE_NAME = "scheduled queries";

  public static final String DELIVERIES_CACHE_NAME = "scheduled deliveries";

  public static final String QUERY_RESULTS_CACHE_NAME = "query results";

  public static final long QUERY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

  private static final DateTimeFormatter ISO_8601_DATE_FORMAT =
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZoneUTC();

  private final CatalogFramework catalogFramework;

  private final PersistentStore persistentStore;

  private final WorkspaceTransformer workspaceTransformer;

  private final List<QueryCourier> queryCourierServiceReferences;

  public QuerySchedulingPostIngestPlugin(
      CatalogFramework catalogFramework,
      PersistentStore persistentStore,
      WorkspaceTransformer workspaceTransformer,
      List<QueryCourier> serviceReferences) {
    this.catalogFramework = catalogFramework;
    this.persistentStore = persistentStore;
    this.workspaceTransformer = workspaceTransformer;
    this.queryCourierServiceReferences = serviceReferences;

    LOGGER.trace("Query scheduling plugin created!");
  }

  private static Fallible<Ignite> getIgnite() {
    if (Ignition.state() != IgniteState.STARTED) {
      return error(
          "An Ignite instance for scheduling and storing jobs is not currently available!");
    }

    return of(Ignition.ignite());
  }

  private static <T> Fallible<IgniteCache<String, T>> getIgniteCache(
      final String cacheName, boolean create) {
    return getIgnite()
        .tryMap(
            ignite -> {
              try {
                if (create) return of(ignite.getOrCreateCache(cacheName));

                return ofNullable(
                    ignite.cache(cacheName),
                    "A cache named \"%s\" does not currently exist",
                    cacheName);
              } catch (CacheException exception) {
                return error(
                    "There was a problem retrieving cache for %s: %s",
                    cacheName, exception.getMessage());
              }
            });
  }

  private static Fallible<IgniteCache<String, Set<String>>> getRunningQueriesCache(boolean create) {
    return getIgniteCache(QUERIES_CACHE_NAME, create);
  }

  private static Fallible<IgniteCache<String, Set<String>>> getDeliveryScheduleCache(
      boolean create) {
    return getIgniteCache(DELIVERIES_CACHE_NAME, create);
  }

  private static Fallible<IgniteScheduler> getScheduler() {
    return getIgnite().map(Ignite::scheduler);
  }

  private Fallible<?> checkAndScheduleDelivery(
      final IgniteScheduler scheduler,
      final Supplier<Fallible<IgniteCache<String, Set<String>>>> runningCache,
      final String queryMetacardId,
      final boolean delayed,
      final int hours,
      final int minutes,
      Map<String, Object> scheduleData,
      final PartialDelivery partialDelivery) {
    DateTime now = DateTime.now();
    DateTime scheduled = now.withHourOfDay(hours).withMinuteOfHour(minutes);
    if (scheduled.compareTo(now) < 0) {
      scheduled = scheduled.plusDays(1);
    }

    Fallible<IgniteCache<String, Map<String, String>>> queryResultCache =
        this.getIgniteCache(QUERY_RESULTS_CACHE_NAME, true);

    if (queryResultCache.hasError())
      return queryResultCache.prependToError(
          "Unable to retrieve \"%s\" cache from ignite", QUERIES_CACHE_NAME);

    RepetitionTimeUnit unit = RepetitionTimeUnit.DAYS;
    if (!delayed) {
      String repUnit = (String) scheduleData.get(ScheduleMetacardTypeImpl.SCHEDULE_UNIT);
      unit = RepetitionTimeUnit.valueOf(repUnit.toUpperCase());
    }
    return scheduleJob(
        scheduler,
        partialDelivery.apply(
            delayed,
            hours,
            minutes,
            scheduleData,
            queryResultCache.or(
                /* Should never occur, but we need the value here not Fallible */ null)),
        runningCache,
        unit,
        scheduled,
        delayed,
        hours,
        minutes,
        queryMetacardId);
  }

  private Fallible<?> checkAndScheduleJob(
      final IgniteScheduler scheduler,
      final Supplier<Fallible<IgniteCache<String, Set<String>>>> runningCache,
      final String queryMetacardId,
      final int scheduleInterval,
      final String scheduleStartString,
      final String scheduleEndString,
      final String scheduleUnit,
      final PartialJob partialJob) {

    if (scheduleInterval <= 0) {
      return error("A task cannot be executed every %d %s!", scheduleInterval, scheduleUnit);
    }

    DateTime end;
    try {
      end = DateTime.parse(scheduleEndString, ISO_8601_DATE_FORMAT);
    } catch (DateTimeParseException | IllegalArgumentException exception) {
      end = DateTime.now().plusYears(1);
    }

    DateTime start;
    try {
      start = DateTime.parse(scheduleStartString, ISO_8601_DATE_FORMAT);
    } catch (DateTimeParseException | IllegalArgumentException exception) {
      start = DateTime.now();
    }

    final RepetitionTimeUnit unit;
    try {
      unit = RepetitionTimeUnit.valueOf(scheduleUnit.toUpperCase());
    } catch (IllegalArgumentException exception) {
      return error(
          "The unit of time \"%s\" for the scheduled query time interval is not recognized!",
          scheduleUnit);
    }

    Fallible<IgniteCache<String, Map<String, String>>> queryResultCache =
        this.getIgniteCache(QUERY_RESULTS_CACHE_NAME, true);

    if (queryResultCache.hasError())
      return queryResultCache.prependToError(
          "Unable to retrieve \"%s\" cache from ignite", QUERIES_CACHE_NAME);

    return scheduleJob(
        scheduler,
        partialJob.apply(
            start,
            end,
            scheduleInterval,
            queryResultCache.or(
                /* Should never occur, but we need the value here not Fallible */ null)),
        runningCache,
        unit,
        start,
        queryMetacardId);
  }

  private Fallible scheduleJob(
      final IgniteScheduler scheduler,
      final SchedulableFuture executor,
      final Supplier<Fallible<IgniteCache<String, Set<String>>>> runningCache,
      final RepetitionTimeUnit unit,
      final DateTime start,
      final String metacardId) {
    return scheduleJob(scheduler, executor, runningCache, unit, start, false, 0, 0, metacardId);
  }

  private Fallible scheduleJob(
      final IgniteScheduler scheduler,
      final SchedulableFuture executor,
      final Supplier<Fallible<IgniteCache<String, Set<String>>>> runningCache,
      final RepetitionTimeUnit unit,
      final DateTime start,
      final boolean delayed,
      final int hours,
      final int minutes,
      final String metacardId) {
    final SchedulerFuture job;
    synchronized (this) {
      try {
        if (delayed) {
          job = scheduler.scheduleLocal(executor, minutes + " " + hours + " * * *");
        } else {
          job = scheduler.scheduleLocal(executor, unit.makeCronToRunEachUnit(start));
        }
      } catch (Exception exception) {
        return error(
            "A problem occurred attempting to schedule a job for metacard \"%s\": %s",
            metacardId, exception.getMessage());
      }
      executor.setJob(job);
      runningCache
          .get()
          .ifValue(
              runningQueries -> {
                final Set<String> runningJobIDsForQuery;
                if (runningQueries.containsKey(metacardId)) {
                  runningJobIDsForQuery = runningQueries.get(metacardId);
                } else {
                  runningJobIDsForQuery = new HashSet<>();
                }

                runningJobIDsForQuery.add(job.id());

                // Because JCache implementations, including IgniteCache, are intended to return
                // copies of any requested
                // data items instead of mutable references, the modified map must be replaced in
                // the cache.
                runningQueries.put(metacardId, runningJobIDsForQuery);
              });
    }

    return success();
  }

  private Fallible<?> readScheduleDataAndScheduleQuery(
      final IgniteScheduler scheduler,
      final String queryMetacardTitle,
      final String queryMetacardId,
      final Map<String, Object> queryMetacardData,
      final Map<String, Object> scheduleData) {
    return MapUtils.tryGet(scheduleData, ScheduleMetacardTypeImpl.IS_SCHEDULED, Boolean.class)
        .tryMap(
            isScheduled -> {
              if (!isScheduled) {
                return success().mapValue(null);
              }

              return MapUtils.tryGetAndRun(
                  scheduleData,
                  ScheduleMetacardTypeImpl.SCHEDULE_USER_ID,
                  String.class,
                  ScheduleMetacardTypeImpl.SCHEDULE_AMOUNT,
                  Integer.class,
                  ScheduleMetacardTypeImpl.SCHEDULE_UNIT,
                  String.class,
                  ScheduleMetacardTypeImpl.SCHEDULE_START,
                  String.class,
                  ScheduleMetacardTypeImpl.SCHEDULE_END,
                  String.class,
                  (scheduleUserID,
                      scheduleInterval,
                      scheduleUnit,
                      scheduleStartString,
                      scheduleEndString) ->
                      checkAndScheduleJob(
                          scheduler,
                          () -> getRunningQueriesCache(true),
                          queryMetacardId,
                          scheduleInterval,
                          scheduleStartString,
                          scheduleEndString,
                          scheduleUnit,
                          (start, end, interval, cache) ->
                              new QueryExecutor(
                                  catalogFramework,
                                  cache,
                                  queryMetacardId,
                                  queryMetacardTitle,
                                  queryMetacardData,
                                  scheduleUserID,
                                  interval,
                                  start,
                                  end)));
            });
  }

  private void readScheduleDataAndScheduleDelivery(
      final IgniteScheduler scheduler,
      final String queryMetacardId,
      final String queryMetacardTitle,
      final Map<String, Object> deliveryData,
      final Map<String, Object> scheduleData) {

    Fallible<List<QueryCourier>> serviceReferences = Fallible.of(queryCourierServiceReferences);

    Collection<String> deliveryIds =
        (Collection<String>)
            deliveryData.get(DeliveryScheduleMetacardTypeImpl.SCHEDULE_DELIVERY_IDS);
    String userId =
        (String) deliveryData.get(DeliveryScheduleMetacardTypeImpl.DELIVERY_SCHEDULE_USER_ID);
    Boolean delayedScheduled =
        (Boolean) deliveryData.get(DeliveryScheduleMetacardTypeImpl.DELIVERY_SCHEDULED);
    Integer hours =
        (Integer) deliveryData.get(DeliveryScheduleMetacardTypeImpl.DELIVERY_SCHEDULE_HOURS);
    Integer minutes =
        (Integer) deliveryData.get(DeliveryScheduleMetacardTypeImpl.DELIVERY_SCHEDULE_MINUTES);

    PartialDelivery partialDelivery =
        (delayed, hrs, mins, queryScheduleProps, cache) ->
            new DeliveryExecutor(
                persistentStore,
                serviceReferences,
                cache,
                queryMetacardId,
                queryMetacardTitle,
                deliveryIds,
                userId,
                delayed,
                hrs,
                mins,
                queryScheduleProps);

    checkAndScheduleDelivery(
        scheduler,
        () -> getDeliveryScheduleCache(true),
        queryMetacardId,
        delayedScheduled,
        hours,
        minutes,
        scheduleData,
        partialDelivery);
  }

  private Fallible<?> readQueryMetacardAndSchedule(final Map<String, Object> queryMetacardData) {
    return getScheduler()
        .tryMap(
            scheduler ->
                MapUtils.tryGetAndRun(
                    queryMetacardData,
                    Metacard.ID,
                    String.class,
                    Metacard.TITLE,
                    String.class,
                    QueryMetacardTypeImpl.QUERY_SCHEDULES,
                    List.class,
                    QueryMetacardTypeImpl.QUERY_DELIVERIES,
                    List.class,
                    (queryMetacardID, queryMetacardTitle, schedulesData, deliveriesData) -> {
                      Iterator<Map<String, Object>> schItr = schedulesData.listIterator();
                      Iterator<Map<String, Object>> delItr = deliveriesData.listIterator();
                      while (schItr.hasNext() && delItr.hasNext()) {
                        Map<String, Object> scheduleData = schItr.next();
                        Map<String, Object> deliveryData = delItr.next();
                        readScheduleDataAndScheduleQuery(
                            scheduler,
                            queryMetacardTitle,
                            queryMetacardID,
                            queryMetacardData,
                            scheduleData);
                        readScheduleDataAndScheduleDelivery(
                            scheduler,
                            queryMetacardID,
                            queryMetacardTitle,
                            deliveryData,
                            scheduleData);
                      }
                      return of(Arrays.asList());
                    }));
  }

  private static Fallible cancelSchedule(final String queryMetacardId) {
    return forEach(
        Stream.of(getRunningQueriesCache(false), getDeliveryScheduleCache(false)),
        cache ->
            cache
                .tryMap(
                    runningQueries -> {
                      try {
                        runningQueries.remove(queryMetacardId);
                      } catch (TransactionException exception) {
                        return error(
                            "There was a problem attempting to cancel a job for the query metacard \"%s\": %s",
                            queryMetacardId, exception.getMessage());
                      }
                      return success();
                    })
                .prependToError("Schedule cancellation error: "));
  }

  private Fallible<?> readQueryMetacardAndCancelSchedule(
      final Map<String, Object> queryMetacardData) {
    return MapUtils.tryGetAndRun(
        queryMetacardData,
        Metacard.ID,
        String.class,
        QuerySchedulingPostIngestPlugin::cancelSchedule);
  }

  private Fallible<?> processMetacard(
      Metacard workspaceMetacard, Function<Map<String, Object>, Fallible<?>> metacardAction) {
    if (!WorkspaceMetacardImpl.isWorkspaceMetacard(workspaceMetacard)) {
      return success();
    }

    final Map<String, Object> workspaceMetacardData =
        workspaceTransformer.transform(workspaceMetacard);

    if (!workspaceMetacardData.containsKey(WorkspaceAttributes.WORKSPACE_QUERIES)) {
      return success();
    }

    return MapUtils.tryGet(workspaceMetacardData, WorkspaceAttributes.WORKSPACE_QUERIES, List.class)
        .tryMap(
            queryMetacardsData ->
                forEach(
                    (List<Map<String, Object>>) queryMetacardsData,
                    queryMetacardData -> {
                      if (!queryMetacardData.containsKey(QueryMetacardTypeImpl.QUERY_SCHEDULES)
                          && !queryMetacardData.containsKey(
                              QueryMetacardTypeImpl.QUERY_DELIVERIES)) {
                        return success();
                      }

                      return metacardAction.apply(queryMetacardData);
                    }));
  }

  @Override
  public CreateResponse process(CreateResponse creation) throws PluginExecutionException {
    LOGGER.trace("Processing creation...");

    forEach(
            creation.getCreatedMetacards(),
            newMetacard ->
                processMetacard(newMetacard, this::readQueryMetacardAndSchedule)
                    .prependToError(
                        "There was an error attempting to schedule delivery job(s) for the new workspace metacard \"%s\": ",
                        newMetacard.getId()))
        .elseThrow(PluginExecutionException::new);

    return creation;
  }

  @Override
  public UpdateResponse process(UpdateResponse updates) throws PluginExecutionException {
    LOGGER.trace("Processing update...");

    forEach(
            updates.getUpdatedMetacards(),
            update ->
                processMetacard(update.getOldMetacard(), this::readQueryMetacardAndCancelSchedule)
                    .prependToError(
                        "There was an error attempting to cancel the scheduled delivery job(s) for the pre-update version of workspace metacard \"%s\": ",
                        update.getOldMetacard().getId()),
            update ->
                processMetacard(update.getNewMetacard(), this::readQueryMetacardAndSchedule)
                    .prependToError(
                        "There was an error attempting to schedule delivery job(s) for the post-update version of workspace metacard \"%s\": ",
                        update.getNewMetacard().getId()))
        .elseThrow(PluginExecutionException::new);

    return updates;
  }

  @Override
  public DeleteResponse process(DeleteResponse deletion) throws PluginExecutionException {
    LOGGER.trace("Processing deletion...");

    forEach(
            deletion.getDeletedMetacards(),
            deletedMetacard ->
                processMetacard(deletedMetacard, this::readQueryMetacardAndCancelSchedule)
                    .prependToError(
                        "There was an error attempting to cancel the scheduled delivery job(s) for the deleted workspace metacard \"%s\": ",
                        deletedMetacard.getId()))
        .elseThrow(PluginExecutionException::new);

    return deletion;
  }
}