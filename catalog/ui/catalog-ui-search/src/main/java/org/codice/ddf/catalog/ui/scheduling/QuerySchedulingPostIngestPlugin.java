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
import ddf.catalog.operation.CreateResponse;
import ddf.catalog.operation.DeleteResponse;
import ddf.catalog.operation.UpdateResponse;
import ddf.catalog.plugin.PluginExecutionException;
import ddf.catalog.plugin.PostIngestPlugin;
import ddf.util.Fallible;
import ddf.util.MapUtils;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.cache.CacheException;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteScheduler;
import org.apache.ignite.IgniteState;
import org.apache.ignite.Ignition;
import org.apache.ignite.scheduler.SchedulerFuture;
import org.apache.ignite.transactions.TransactionException;
import org.codice.ddf.catalog.ui.metacard.workspace.QueryMetacardTypeImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceAttributes;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceMetacardImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceTransformer;
import org.codice.ddf.persistence.PersistentStore;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuerySchedulingPostIngestPlugin implements PostIngestPlugin {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(QuerySchedulingPostIngestPlugin.class);

  public static final String DELIVERY_METHODS_KEY = "deliveryMethods";

  public static final String DELIVERY_METHOD_ID_KEY = "deliveryId";

  public static final String DELIVERY_PARAMETERS_KEY = "deliveryParameters";

  public static final String QUERIES_CACHE_NAME = "scheduled queries";

  public static final long QUERY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

  private static final DateTimeFormatter ISO_8601_DATE_FORMAT =
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZoneUTC();

  private final BundleContext bundleContext =
      FrameworkUtil.getBundle(QuerySchedulingPostIngestPlugin.class).getBundleContext();

  private final CatalogFramework catalogFramework;

  private final PersistentStore persistentStore;

  private final WorkspaceTransformer workspaceTransformer;

  public QuerySchedulingPostIngestPlugin(
      CatalogFramework catalogFramework,
      PersistentStore persistentStore,
      WorkspaceTransformer workspaceTransformer) {
    this.catalogFramework = catalogFramework;
    this.persistentStore = persistentStore;
    this.workspaceTransformer = workspaceTransformer;

    LOGGER.trace("Query scheduling plugin created!");
  }

  private static Fallible<Ignite> getIgnite() {
    if (Ignition.state() != IgniteState.STARTED) {
      return error(
          "An Ignite instance for scheduling and storing jobs is not currently available!");
    }

    return of(Ignition.ignite());
  }

  static Fallible<IgniteCache<String, HashSet<String>>> getRunningQueriesCache(boolean create) {
    return getIgnite()
        .tryMap(
            ignite -> {
              try {
                if (create) {
                  return of(ignite.getOrCreateCache(QUERIES_CACHE_NAME));
                } else {
                  return ofNullable(
                      ignite.cache(QUERIES_CACHE_NAME),
                      "A cache does not currently exist for scheduled query data!");
                }
              } catch (CacheException exception) {
                return error(
                    "There was a problem attempting to retrieve a cache for running query data: %s",
                    exception.getMessage());
              }
            });
  }

  private static Fallible<IgniteScheduler> getScheduler() {
    return getIgnite().map(Ignite::scheduler);
  }

  private Fallible<?> schedule(
      final IgniteScheduler scheduler,
      final String queryMetacardTitle,
      final String queryMetacardID,
      final Map<String, Object> queryMetacardData,
      final String scheduleUserID,
      final int scheduleInterval,
      final String scheduleUnit,
      final String scheduleStartString,
      final String scheduleEndString,
      final List<String> scheduleDeliveryIDs) {
    if (scheduleInterval <= 0) {
      return error("A task cannot be executed every %d %s!", scheduleInterval, scheduleUnit);
    }

    final DateTime end;
    try {
      end = DateTime.parse(scheduleEndString, ISO_8601_DATE_FORMAT);
    } catch (DateTimeParseException exception) {
      return error(
          "The end date attribute of this metacard, \"%s\", could not be parsed: %s",
          scheduleStartString, exception.getMessage());
    }

    final DateTime start;
    try {
      start = DateTime.parse(scheduleStartString, ISO_8601_DATE_FORMAT);
    } catch (DateTimeParseException exception) {
      return error(
          "The start date attribute of this metacard, \"%s\", could not be parsed: %s",
          scheduleStartString, exception.getMessage());
    }

    final RepetitionTimeUnit unit;
    try {
      unit = RepetitionTimeUnit.valueOf(scheduleUnit.toUpperCase());
    } catch (IllegalArgumentException exception) {
      return error(
          "The unit of time \"%s\" for the scheduled query time interval is not recognized!",
          scheduleUnit);
    }

    final QueryDeliveryExecutor deliveryExecutor =
        new QueryDeliveryExecutor(
            this,
            queryMetacardID,
            queryMetacardTitle,
            queryMetacardData,
            scheduleDeliveryIDs,
            scheduleUserID,
            scheduleInterval,
            start,
            end);
    final SchedulerFuture<?> job;

    synchronized (this) {
      try {
        job = scheduler.scheduleLocal(deliveryExecutor, unit.makeCronToRunEachUnit(start));
      } catch (IgniteException exception) {
        return error(
            "There was a problem attempting to schedule a job for a query metacard \"%s\": %s",
            queryMetacardID, exception.getMessage());
      }
      deliveryExecutor.setJob(job);
      getRunningQueriesCache(true)
          .ifValue(
              runningQueries -> {
                final HashSet<String> runningJobIDsForQuery;
                if (runningQueries.containsKey(queryMetacardID)) {
                  runningJobIDsForQuery = runningQueries.get(queryMetacardID);
                } else {
                  runningJobIDsForQuery = new HashSet<>();
                }

                runningJobIDsForQuery.add(job.id());

                // Because JCache implementations, including IgniteCache, are intended to return
                // copies of any requested
                // data items instead of mutable references, the modified map must be replaced in
                // the cache.
                runningQueries.put(queryMetacardID, runningJobIDsForQuery);
              });
    }

    return success();
  }

  private Fallible<?> readScheduleDataAndSchedule(
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
                  ScheduleMetacardTypeImpl.SCHEDULE_DELIVERY_IDS,
                  List.class,
                  (scheduleUserID,
                      scheduleInterval,
                      scheduleUnit,
                      scheduleStartString,
                      scheduleEndString,
                      scheduleDeliveries) ->
                      schedule(
                          scheduler,
                          queryMetacardTitle,
                          queryMetacardId,
                          queryMetacardData,
                          scheduleUserID,
                          scheduleInterval,
                          scheduleUnit,
                          scheduleStartString,
                          scheduleEndString,
                          (List<String>) scheduleDeliveries));
            });
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
                    (queryMetacardID, queryMetacardTitle, schedulesData) ->
                        forEach(
                            (List<Map<String, Object>>) schedulesData,
                            scheduleData ->
                                readScheduleDataAndSchedule(
                                    scheduler,
                                    queryMetacardTitle,
                                    queryMetacardID,
                                    queryMetacardData,
                                    scheduleData))));
  }

  private static Fallible<?> cancelSchedule(final String queryMetacardId) {
    return getRunningQueriesCache(false)
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
            });
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
                      if (!queryMetacardData.containsKey(QueryMetacardTypeImpl.QUERY_SCHEDULES)) {
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

  public BundleContext getBundleContext() {
    return bundleContext;
  }

  public CatalogFramework getCatalogFramework() {
    return catalogFramework;
  }

  public PersistentStore getPersistentStore() {
    return persistentStore;
  }
}
