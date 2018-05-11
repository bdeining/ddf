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
package org.codice.ddf.catalog.ui.scheduling

import ddf.catalog.CatalogFramework
import ddf.catalog.data.Metacard
import ddf.catalog.operation.CreateResponse
import ddf.catalog.operation.DeleteResponse
import ddf.catalog.operation.Update
import ddf.catalog.operation.UpdateResponse
import org.apache.ignite.*
import org.apache.ignite.scheduler.SchedulerFuture
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceTransformer
import org.codice.ddf.catalog.ui.scheduling.subscribers.QueryCourier
import org.codice.ddf.persistence.PersistentStore
import org.joda.time.DateTime
import org.junit.Rule
import org.mockito.Mockito
import org.powermock.api.mockito.PowerMockito
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.modules.junit4.rule.PowerMockRule
import spock.lang.Specification

@PrepareForTest([Ignition.class])
class QuerySchedulingPostIngestPluginSpec extends Specification {

    @Rule PowerMockRule powerMockRule = new PowerMockRule()

    final def dataSample = ['queries': [
            ['id': 'testId', 'title': 'test title',
             'schedules': [['userId': 'test@connexta.com', 'scheduleAmount': 2, 'isScheduled': true, 'scheduleUnit': 'days', 'scheduleStart': DateTime.now().toString(), 'scheduleEnd': DateTime.now().plusMinutes(5).toString()]],
             'deliveries': [['userId': 'test@connexta.com', 'scheduleInterval': 5, 'isScheduled': true, 'scheduleUnit': 'days', 'scheduleStart': DateTime.now().toString(), 'scheduleEnd': DateTime.now().plusMinutes(5).toString(), 'deliveryIds': ['id1', 'id2']]]
            ]]
    ]

    def 'test process create response'() {
        setup:
        PowerMockito.mockStatic(Ignition.class)

        def mockJob = Mock(SchedulerFuture)

        def mockScheduler = Mock(IgniteScheduler) {
            2 * scheduleLocal(_, _) >> mockJob
        }

        def mockCache = Mock(IgniteCache)

        def mockIgnite = Mock(Ignite) {
            1 * scheduler() >> mockScheduler
            4 * getOrCreateCache(_) >> mockCache
            0 * cache(_) >> mockCache
        }

        Mockito.when(Ignition.state()).thenReturn(IgniteState.STARTED)
        Mockito.when(Ignition.ignite()).thenReturn(mockIgnite)

        def mockCatalogFramework = Mock(CatalogFramework)
        def mockPeristenStore = Mock(PersistentStore)
        def mockWorkspaceTransformer = Mock(WorkspaceTransformer)
        def mockServiceReferences = [Mock(QueryCourier)]

        def mockCreateResponse = Mock(CreateResponse)

        def plugin = Spy(QuerySchedulingPostIngestPlugin, constructorArgs:
                [mockCatalogFramework, mockPeristenStore, mockWorkspaceTransformer, mockServiceReferences])

        when:
        plugin.process(mockCreateResponse)

        then:
        1 * mockCreateResponse.getCreatedMetacards() >> [Mock(Metacard){
            getTags() >> (['workspace'] as Set)
        }]

        1 * mockWorkspaceTransformer.transform(_) >> dataSample
    }

    def 'test process update response' () {
        setup:
        PowerMockito.mockStatic(Ignition.class)

        def mockJob = Mock(SchedulerFuture)

        def mockScheduler = Mock(IgniteScheduler) {
            2 * scheduleLocal(_, _) >> mockJob
        }

        def mockJobCache = Mock(IgniteCache) {
            2 * remove(_) //Cancel jobs, one query and one delivery
            2 * put(_, _) //Create jobs, one query and one delivery
            2 * containsKey('testId') >> true
            2 * get(_) >> ([] as Set)
        }

        def mockIgnite = Mock(Ignite) {
            1 * scheduler() >> mockScheduler
            4 * getOrCreateCache(_) >> mockJobCache
            2 * cache(_) >> mockJobCache
        }

        Mockito.when(Ignition.state()).thenReturn(IgniteState.STARTED)
        Mockito.when(Ignition.ignite()).thenReturn(mockIgnite)

        def mockCatalogFramework = Mock(CatalogFramework)
        def mockPeristenStore = Mock(PersistentStore)
        def mockWorkspaceTransformer = Mock(WorkspaceTransformer)
        def mockServiceReferences = [Mock(QueryCourier)]

        def mockUpdateResponse = Mock(UpdateResponse)

        def mockOldMetacard = Mock(Metacard) {
            getTags() >> (['workspace'] as Set)
        }
        def mockNewMetacard = Mock(Metacard) {
            getTags() >> (['workspace'] as Set)
        }

        def mockUpdate = Mock(Update) {
            2 * getOldMetacard() >> mockOldMetacard
            2 * getNewMetacard() >> mockNewMetacard
        }

        def plugin = Spy(QuerySchedulingPostIngestPlugin, constructorArgs:
                [mockCatalogFramework, mockPeristenStore, mockWorkspaceTransformer, mockServiceReferences])

        when:
        plugin.process(mockUpdateResponse)

        then:
        1 * mockUpdateResponse.getUpdatedMetacards() >> [mockUpdate]
        2 * mockWorkspaceTransformer.transform(_) >> dataSample
    }

    def 'test process delete response'() {
        setup:
        PowerMockito.mockStatic(Ignition.class)

        def mockJobCache = Mock(IgniteCache) {
            2 * remove(_) //Cancel jobs, one query and one delivery
            0 * put(*_) //No jobs created
            0 * containsKey(*_)
            0 * get(_)
        }

        def mockIgnite = Mock(Ignite) {
            2 * cache(_) >> mockJobCache
        }

        Mockito.when(Ignition.state()).thenReturn(IgniteState.STARTED)
        Mockito.when(Ignition.ignite()).thenReturn(mockIgnite)

        def mockCatalogFramework = Mock(CatalogFramework)
        def mockPeristenStore = Mock(PersistentStore)
        def mockWorkspaceTransformer = Mock(WorkspaceTransformer)
        def mockServiceReferences = [Mock(QueryCourier)]

        def mockDelete = Mock(Metacard) {
            1 * getTags() >> (['workspace'] as Set)
        }

        def mockDeleteResponse = Mock(DeleteResponse)

        def plugin = Spy(QuerySchedulingPostIngestPlugin, constructorArgs:
                [mockCatalogFramework, mockPeristenStore, mockWorkspaceTransformer, mockServiceReferences])

        when:
        plugin.process(mockDeleteResponse)

        then:
        1 * mockDeleteResponse.getDeletedMetacards() >> [mockDelete]
        1 * mockWorkspaceTransformer.transform(_) >> dataSample
    }
}