/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.testing.utils;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.common.config.Configs;

import java.util.List;
import java.util.Map;

public class AbstractQueryWithResults<QueryType>
{
  private final QueryType query;
  private final String description;
  private final List<Map<String, Object>> expectedResults;
  private final List<String> fieldsToTest;

  @JsonCreator
  public AbstractQueryWithResults(
      @JsonProperty("query") QueryType query,
      @JsonProperty("description") String description,
      @JsonProperty("expectedResults") List<Map<String, Object>> expectedResults,
      @JsonProperty("fieldsToTest") List<String> fieldsToTest
  )
  {
    this.query = query;
    this.description = description;
    this.expectedResults = expectedResults;
    this.fieldsToTest = Configs.valueOrDefault(fieldsToTest, List.of());
  }

  @JsonProperty
  public QueryType getQuery()
  {
    return query;
  }

  @JsonProperty
  public String getDescription()
  {
    return description;
  }

  @JsonProperty
  public List<Map<String, Object>> getExpectedResults()
  {
    return expectedResults;
  }

  @JsonProperty
  public List<String> getFieldsToTest()
  {
    return fieldsToTest;
  }

  @Override
  public String toString()
  {
    return "QueryWithResults{" +
           "query=" + query +
           ", expectedResults=" + expectedResults +
           ", fieldsToTest=" + fieldsToTest +
           '}';
  }
}
