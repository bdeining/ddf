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

import ddf.catalog.data.Result
import ddf.util.Fallible
import groovy.json.JsonOutput
import org.apache.ignite.IgniteCache
import org.codice.ddf.catalog.ui.scheduling.subscribers.QueryCourier
import org.codice.ddf.persistence.PersistentStore
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import spock.lang.Specification

import java.nio.charset.Charset
import java.util.stream.Collectors

class DeliveryExecutorSpec extends Specification {
    final static def JSON_PREFERENCES_STRING = """
                {
                  "deliveryMethods": [{
                    "deliveryId": "id1",
                    "deliveryParameters": {
                      "email": "test@connexta.com"
                    },
                    "deliveryType": "email",
                    "name": "Email"
                  }]
                }"""

    def mockResult
    def mockServiceCache

    def mockQueryCourierService
    def serviceReferences

    def startTime
    def endTime

    def setup() {
        mockResult = Mock(Result)
        mockServiceCache = Mock(IgniteCache) {
            (1.._) * containsKey(_) >> true
        }
        mockQueryCourierService = Mock(QueryCourier)
        serviceReferences = Fallible.of([mockQueryCourierService])
        startTime = DateTime.now()
        endTime = DateTime.now().plusMinutes(2)
    }

    def 'test create executor and schedule job'() {
        setup:
        def mockPersistenceStore = Mock(PersistentStore) {
            1 * get(_, _) >> [['preferences_json_bin' : JSON_PREFERENCES_STRING.getBytes(Charset.defaultCharset())]]
        }
        def mockQueryCache = Mock(IgniteCache) {
            (1.._) * get(_) >> [("${DateTime.now()}".toString()) : ("${JsonOutput.toJson([mockResult])}".toString())]
        }
        def deliveryExecutor = Spy(DeliveryExecutor, constructorArgs: [
                mockPersistenceStore, serviceReferences, mockQueryCache, 'testId', 'test title', ['id1', 'id2'],
                'test@connexta.com', 1, startTime, endTime]) {
            (1.._) * unwrapCache() >> Fallible.of(mockServiceCache)
        }

        when:
        deliveryExecutor.run()

        then:
        1 * mockQueryCourierService.deliver(*_)
        1 * mockQueryCourierService.getDeliveryType() >> 'email'
        1 * deliveryExecutor.run()
    }

    def 'test run job with empty result - no delivery'() {
        setup:
        def mockQueryCache = Mock(IgniteCache) {
            (1.._) * get(_) >> []
        }
        def mockPersistenceStore = Mock(PersistentStore) {
            0 * get(*_)
        }
        def deliveryExecutor = Spy(DeliveryExecutor, constructorArgs: [
                mockPersistenceStore, serviceReferences, mockQueryCache, 'testId', 'test title', ['id1', 'id2'],
                'test@connexta.com', 1, startTime, endTime]) {
            (1.._) * unwrapCache() >> Fallible.of(mockServiceCache)
        }

        when:
        deliveryExecutor.run()

        then:
        1 * deliveryExecutor.run()
        0 * mockQueryCourierService.deliver(*_)
    }

    def 'test run job with multiple results - multiple deliveries'() {
        setup:
        def resultMap = (1..5).stream().map({ count ->
            return [(DateTime.now().minusMinutes(count).toString()): ("${JsonOutput.toJson([mockResult])}".toString())]
        }).reduce({map, entry -> map << entry}).orElseGet({ -> []})
        def mockQueryCache = Mock(IgniteCache) {
            (1.._) * get(_) >> new HashMap<>(resultMap)
            5 * put(_, _)
        }
        def mockPersistenceStore = Mock(PersistentStore) {
            5 * get(_, _) >> [['preferences_json_bin' : JSON_PREFERENCES_STRING.getBytes(Charset.defaultCharset())]]
        }
        def deliveryExecutor = Spy(DeliveryExecutor, constructorArgs: [
                mockPersistenceStore, serviceReferences, mockQueryCache, 'testId', 'test title', ['id1'],
                'test@connexta.com', 1, startTime, endTime]) {
            (1.._) * unwrapCache() >> Fallible.of(mockServiceCache)
        }

        when:
        deliveryExecutor.run()

        then:
        5 * mockQueryCourierService.deliver(*_)
        5 * mockQueryCourierService.getDeliveryType() >> 'email'
        1 * deliveryExecutor.run()
    }
}