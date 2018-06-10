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
import java.util.function.BiFunction;
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
      final String deliveryTime,
      Map<String, Object> scheduleData,
      final PartialJob partialDelivery) {

    Boolean queryIsScheduled = (Boolean) scheduleData.get(ScheduleMetacardTypeImpl.IS_SCHEDULED);
    if (!queryIsScheduled) {
      return success();
    }
    DateTime queryScheduleStart =
        DateTime.parse(
            (String) scheduleData.get(ScheduleMetacardTypeImpl.SCHEDULE_START),
            ISO_8601_DATE_FORMAT);
    DateTime queryScheduleEnd =
        DateTime.parse(
            (String) scheduleData.get(ScheduleMetacardTypeImpl.SCHEDULE_END), ISO_8601_DATE_FORMAT);
    RepetitionTimeUnit queryScheduleUnit =
        RepetitionTimeUnit.valueOf(
            ((String) scheduleData.get(ScheduleMetacardTypeImpl.SCHEDULE_UNIT)).toUpperCase());
    Integer queryScheduleAmount =
        (Integer) scheduleData.get(ScheduleMetacardTypeImpl.SCHEDULE_AMOUNT);

    final long queryIntervalInMillis;
    final long lengthOfDayInMillis =
        queryScheduleStart.plusDays(1).getMillis() - queryScheduleStart.getMillis();
    switch (queryScheduleUnit) {
      case MINUTES:
        queryIntervalInMillis =
            queryScheduleStart.plusMinutes(queryScheduleAmount).getMillis()
                - queryScheduleStart.getMillis();
        break;
      case HOURS:
        queryIntervalInMillis =
            queryScheduleStart.plusHours(queryScheduleAmount).getMillis()
                - queryScheduleStart.getMillis();
        break;
      case DAYS:
        queryIntervalInMillis =
            queryScheduleStart.plusDays(queryScheduleAmount).getMillis()
                - queryScheduleStart.getMillis();
        break;
      case WEEKS:
        queryIntervalInMillis =
            queryScheduleStart.plusWeeks(queryScheduleAmount).getMillis()
                - queryScheduleStart.getMillis();
        break;
      case MONTHS:
        queryIntervalInMillis =
            queryScheduleStart.plusMonths(queryScheduleAmount).getMillis()
                - queryScheduleStart.getMillis();
        break;
      case YEARS:
        queryIntervalInMillis =
            queryScheduleStart.plusYears(queryScheduleAmount).getMillis()
                - queryScheduleStart.getMillis();
        break;
      default:
        queryIntervalInMillis = 0;
        break;
    }

    DateTime deliveryScheduleStart;
    DateTime deliveryScheduleEnd;
    RepetitionTimeUnit deliveryScheduleUnit;
    Integer deliveryScheduleAmount;

    /*
     Logic:
     if delivery execution is delayed:
       - parse the delivery time
       - ensure it comes after the query start time
       - check to see if the query repeats more or less than once per day
       - if it repeats less than once per day (interval > one day):
         - make the delivery interval a shifted copy of the query interval (i.e. query every 8 months -> delivery every 8 months)
       - otherwise:
         - make the delivery interval once per day, and make it end at a shifted end time
         TODO: There might be a bug here if QueryStart = 2PM, QueryEnd = 5PM, repeat every 1hr, deliver at 10PM
     otherwise:
       - make the delivery execution the same as the query execution.
       Include a minimal offset to avoid "delivery and query run at same time" -> "delivery technically runs before query" scheduling decisions
       I'd rather have the delivery happen a minute later than scheduled than potentially wait a whole additional Query Schedule Unit (i.e. 8 months)
    */
    if (delayed) {
      deliveryScheduleStart = DateTime.parse(deliveryTime, ISO_8601_DATE_FORMAT);
      if (deliveryScheduleStart.compareTo(queryScheduleStart) < 0) {
        deliveryScheduleStart = deliveryScheduleStart.plusDays(1);
      }
      if (queryIntervalInMillis > lengthOfDayInMillis) {
        deliveryScheduleEnd =
            queryScheduleEnd.plus(
                deliveryScheduleStart.getMillis() - queryScheduleStart.getMillis());
        deliveryScheduleUnit = queryScheduleUnit;
        deliveryScheduleAmount = queryScheduleAmount;
      } else {
        deliveryScheduleEnd =
            deliveryScheduleStart.plus(
                queryScheduleEnd.getMillis() - queryScheduleStart.getMillis());
        deliveryScheduleUnit = RepetitionTimeUnit.DAYS;
        deliveryScheduleAmount = 1;
      }
    } else {
      deliveryScheduleStart = queryScheduleStart.plusMinutes(1);
      deliveryScheduleEnd = queryScheduleEnd.plusMinutes(1);
      deliveryScheduleUnit = queryScheduleUnit;
      deliveryScheduleAmount = queryScheduleAmount;
    }

    Fallible<IgniteCache<String, Map<String, String>>> queryResultCache =
        this.getIgniteCache(QUERY_RESULTS_CACHE_NAME, true);

    if (queryResultCache.hasError())
      return queryResultCache.prependToError(
          "Unable to retrieve \"%s\" cache from ignite", QUERIES_CACHE_NAME);

    return scheduleJob(
        scheduler,
        partialDelivery.apply(
            deliveryScheduleStart,
            deliveryScheduleEnd,
            deliveryScheduleAmount,
            queryResultCache.or(
                /* Should never occur, but we need the value here not Fallible */ null)),
        runningCache,
        deliveryScheduleUnit,
        deliveryScheduleStart,
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
    final SchedulerFuture job;
    synchronized (this) {
      try {
        job = scheduler.scheduleLocal(executor, unit.makeCronToRunEachUnit(start));
        LOGGER.debug(
            "Scheduled local job for metacardId: {}, starting at: {}, with cron string: {}",
            metacardId,
            start.toString(),
            unit.makeCronToRunEachUnit(start));
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
      final String workspaceMetacardId,
      final String queryMetacardId,
      final String queryMetacardTitle,
      final Map<String, Object> deliveryData,
      final Map<String, Object> queryScheduleData) {

    Fallible<List<QueryCourier>> serviceReferences = Fallible.of(queryCourierServiceReferences);

    Collection<String> deliveryIds =
        (Collection<String>)
            deliveryData.get(DeliveryScheduleMetacardTypeImpl.SCHEDULE_DELIVERY_IDS);
    String userId =
        (String) deliveryData.get(DeliveryScheduleMetacardTypeImpl.DELIVERY_SCHEDULE_USER_ID);
    Boolean delayedScheduled =
        (Boolean) deliveryData.get(DeliveryScheduleMetacardTypeImpl.DELIVERY_SCHEDULED);
    String deliveryTime =
        (String) deliveryData.get(DeliveryScheduleMetacardTypeImpl.DELIVERY_SCHEDULE_TIME);

    PartialJob partialDelivery =
        (deliveryStart, deliveryEnd, deliveryInterval, cache) ->
            new DeliveryExecutor(
                persistentStore,
                serviceReferences,
                cache,
                workspaceMetacardId,
                queryMetacardId,
                queryMetacardTitle,
                deliveryIds,
                userId,
                deliveryInterval,
                deliveryStart,
                deliveryEnd);

    checkAndScheduleDelivery(
        scheduler,
        () -> getDeliveryScheduleCache(true),
        queryMetacardId,
        delayedScheduled,
        deliveryTime,
        queryScheduleData,
        partialDelivery);
  }

  private Fallible<?> readQueryMetacardAndSchedule(
      final Map<String, Object> queryMetacardData, final String workspaceMetacardID) {
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
                            workspaceMetacardID,
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
      final Map<String, Object> queryMetacardData, final String workspaceMetacardId) {
    return MapUtils.tryGetAndRun(
        queryMetacardData,
        Metacard.ID,
        String.class,
        QuerySchedulingPostIngestPlugin::cancelSchedule);
  }

  private Fallible<?> processMetacard(
      Metacard workspaceMetacard,
      BiFunction<Map<String, Object>, String, Fallible<?>> metacardAction) {
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

                      return metacardAction.apply(
                          queryMetacardData, (String) workspaceMetacardData.get(Metacard.ID));
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
