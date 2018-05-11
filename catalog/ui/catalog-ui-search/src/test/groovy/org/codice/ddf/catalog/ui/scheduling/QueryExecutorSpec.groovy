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
import ddf.catalog.data.impl.QueryMetacardTypeImpl
import ddf.catalog.operation.QueryResponse
import ddf.security.Subject
import ddf.util.Fallible
import org.apache.ignite.IgniteCache
import org.codice.ddf.security.common.Security
import org.joda.time.DateTime
import spock.lang.Specification

class QueryExecutorSpec extends Specification {
    def mockServiceCache
    def mockCatalogFramework
    def start
    def end

    def setup() {
        mockServiceCache = Mock(IgniteCache)
        mockCatalogFramework = Mock(CatalogFramework)
        start = DateTime.now()
        end = DateTime.now().plusMinutes(2)
    }

    def 'test create executor and schedule job'() {
        setup:
        def mockQueryResultCache = Mock(IgniteCache) {
            1 * put(_, _)
        }
        def queryMetacardData = [
                (QueryMetacardTypeImpl.QUERY_CQL): 'DISJOINT(buffer(the_geom, 10) , POINT(1 2))',
                (QueryMetacardTypeImpl.QUERY_SORTS): '',
                'src': ['source.1', 'source.2']
        ]
        def mockQueryResponse = Mock(QueryResponse) {
            1 * getResults() >> []
        }

        def mockSecurity = Mock(Security) {
            1 * getGuestSubject(_) >> Mock(Subject) {
                1 * execute(_) >> Fallible.of(new AbstractMap.SimpleEntry<DateTime, QueryResponse>(DateTime.now(), mockQueryResponse))
            }
        }

        def queryExecutor = Spy(QueryExecutor, constructorArgs:
                [mockCatalogFramework, mockQueryResultCache,'metacardId', 'metacard title', queryMetacardData,
                 'test@connexta.com', 5, start, end]
        ) {
            (1.._) * unwrapCache() >> Fallible.of(mockServiceCache)
            1 * getSecurity() >> mockSecurity
        }

        when:
        queryExecutor.run()

        then:
        1 * queryExecutor.run()
        1 * mockServiceCache.containsKey(_) >> true
    }

    def 'test executor with null job - does not execute queries'() {
        setup:
        def mockQueryResultCache = Mock(IgniteCache) {
            0 * put(_, _)
        }

        def mockSecurity = Mock(Security) {
            0 * getGuestSubject(_) >> Mock(Subject)
        }

        def queryExecutor = Spy(QueryExecutor, constructorArgs:
                [mockCatalogFramework, mockQueryResultCache, 'metacardId', 'metacard title', [:], 'test@connexta.com',
                 5, start, end]
        ) {
            1 * unwrapCache()
            0 * getSecurity() >> null
        }

        when:
        queryExecutor.setJob(null)
        queryExecutor.run()

        then:
        1 * queryExecutor.run()
        0 * mockServiceCache.containsKey(_)
    }
}