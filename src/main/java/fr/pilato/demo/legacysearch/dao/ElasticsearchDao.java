/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package fr.pilato.demo.legacysearch.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.pilato.demo.legacysearch.domain.Person;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;

@Repository
public class ElasticsearchDao {
    final Logger logger = LoggerFactory.getLogger(ElasticsearchDao.class);

    @Autowired ObjectMapper mapper;
    @Autowired Client esClient;
    BulkProcessor bulkProcessor;

    @PostConstruct
    public void initBulk() {
        bulkProcessor = BulkProcessor.builder(esClient, new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                logger.debug("going to execute bulk of {} requests", request.numberOfActions());
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                logger.debug("bulk executed {} failures", response.hasFailures() ? "with" : "without");
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                logger.warn("error while executing bulk", failure);
            }
        })
                .setBulkActions(10000)
                .setFlushInterval(TimeValue.timeValueSeconds(5))
                .build();
    }

    public void save(Person person) throws JsonProcessingException {
        byte[] bytes = mapper.writeValueAsBytes(person);
        bulkProcessor.add(new IndexRequest("person", "person", person.getReference()).source(bytes));
    }

    public void delete(String reference) {
        bulkProcessor.add(new DeleteRequest("person", "person", reference));
    }

    public SearchResponse search(QueryBuilder query, Integer from, Integer size) {
        logger.debug("elasticsearch query: {}", query.toString());
        SearchResponse response = esClient.prepareSearch("person")
                .setTypes("person")
                .setQuery(query)
                .addAggregation(
                        AggregationBuilders.terms("by_country").field("country")
                                .subAggregation(AggregationBuilders.dateHistogram("by_year")
                                                .field("dateOfBirth")
                                                .minDocCount(0)
                                                .interval(DateHistogram.Interval.days(3652))
                                                .extendedBounds("1940", "2009")
                                                .format("YYYY")
                                                .subAggregation(AggregationBuilders.avg("avg_children").field("children"))
                                )
                )
                .addAggregation(
                        AggregationBuilders.dateHistogram("by_year")
                                .field("dateOfBirth")
                                .minDocCount(0)
                                .interval(DateHistogram.Interval.YEAR)
                                .extendedBounds("1940", "2009")
                                .format("YYYY")
                )
                .setFrom(from)
                .setSize(size)
                .execute().actionGet();

        logger.debug("elasticsearch response: {} hits", response.getHits().totalHits());
        logger.trace("elasticsearch response: {} hits", response.toString());

        return response;
    }
}
