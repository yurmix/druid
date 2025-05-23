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

package org.apache.druid.benchmark.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.data.input.Row;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.DefaultGenericQueryMetricsFactory;
import org.apache.druid.query.Druids;
import org.apache.druid.query.FinalizeResultsQueryRunner;
import org.apache.druid.query.Order;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.Result;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.aggregation.hyperloglog.HyperUniquesSerde;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.extraction.StrlenExtractionFn;
import org.apache.druid.query.filter.BoundDimFilter;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.query.filter.InDimFilter;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.query.scan.ScanQuery;
import org.apache.druid.query.scan.ScanQueryConfig;
import org.apache.druid.query.scan.ScanQueryEngine;
import org.apache.druid.query.scan.ScanQueryQueryToolChest;
import org.apache.druid.query.scan.ScanQueryRunnerFactory;
import org.apache.druid.query.scan.ScanResultValue;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.query.spec.MultipleSpecificSegmentSpec;
import org.apache.druid.query.spec.QuerySegmentSpec;
import org.apache.druid.segment.IncrementalIndexSegment;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.IndexMergerV9;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.QueryableIndexSegment;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.generator.DataGenerator;
import org.apache.druid.segment.generator.GeneratorBasicSchemas;
import org.apache.druid.segment.generator.GeneratorSchemaInfo;
import org.apache.druid.segment.incremental.AppendableIndexSpec;
import org.apache.druid.segment.incremental.IncrementalIndex;
import org.apache.druid.segment.incremental.IncrementalIndexCreator;
import org.apache.druid.segment.incremental.OnheapIncrementalIndex;
import org.apache.druid.segment.serde.ComplexMetrics;
import org.apache.druid.segment.writeout.OffHeapMemorySegmentWriteOutMediumFactory;
import org.apache.druid.timeline.SegmentId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/* Works with 8GB heap size or greater.  Otherwise there's a good chance of an OOME. */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 10)
@Measurement(iterations = 25)
public class ScanBenchmark
{
  @Param({"200000"})
  private int rowsPerSegment;

  @Param({"basic.A", "basic.B", "basic.C", "basic.D"})
  private String schemaAndQuery;

  @Param({"1000", "99999"})
  private int limit;

  @Param({"NONE", "DESCENDING", "ASCENDING"})
  private static Order ordering;

  private static final Logger log = new Logger(ScanBenchmark.class);
  private static final int RNG_SEED = 9999;
  private static final ObjectMapper JSON_MAPPER;
  private static final IndexMergerV9 INDEX_MERGER_V9;
  private static final IndexIO INDEX_IO;

  private AppendableIndexSpec appendableIndexSpec;
  private DataGenerator generator;
  private QueryRunnerFactory factory;
  private GeneratorSchemaInfo schemaInfo;
  private Druids.ScanQueryBuilder queryBuilder;
  private ScanQuery query;

  static {
    JSON_MAPPER = new DefaultObjectMapper();
    INDEX_IO = new IndexIO(JSON_MAPPER, ColumnConfig.DEFAULT);
    INDEX_MERGER_V9 = new IndexMergerV9(JSON_MAPPER, INDEX_IO, OffHeapMemorySegmentWriteOutMediumFactory.instance());
  }

  private static final Map<String, Map<String, Druids.ScanQueryBuilder>> SCHEMA_QUERY_MAP = new LinkedHashMap<>();

  private void setupQueries()
  {
    // queries for the basic schema
    final Map<String, Druids.ScanQueryBuilder> basicQueries = new LinkedHashMap<>();
    final GeneratorSchemaInfo basicSchema = GeneratorBasicSchemas.SCHEMA_MAP.get("basic");

    final List<String> queryTypes = ImmutableList.of("A", "B", "C", "D");
    for (final String eachType : queryTypes) {
      basicQueries.put(eachType, makeQuery(eachType, basicSchema));
    }

    SCHEMA_QUERY_MAP.put("basic", basicQueries);
  }

  private static Druids.ScanQueryBuilder makeQuery(final String name, final GeneratorSchemaInfo basicSchema)
  {
    switch (name) {
      case "A":
        return basicA(basicSchema);
      case "B":
        return basicB(basicSchema);
      case "C":
        return basicC(basicSchema);
      case "D":
        return basicD(basicSchema);
      default:
        return null;
    }
  }

  /* Just get everything */
  private static Druids.ScanQueryBuilder basicA(final GeneratorSchemaInfo basicSchema)
  {
    final QuerySegmentSpec intervalSpec =
        new MultipleIntervalSegmentSpec(Collections.singletonList(basicSchema.getDataInterval()));

    return Druids.newScanQueryBuilder()
                 .dataSource("blah")
                 .intervals(intervalSpec)
                 .order(ordering);
  }

  private static Druids.ScanQueryBuilder basicB(final GeneratorSchemaInfo basicSchema)
  {
    final QuerySegmentSpec intervalSpec =
        new MultipleIntervalSegmentSpec(Collections.singletonList(basicSchema.getDataInterval()));

    List<String> dimHyperUniqueFilterVals = new ArrayList<>();
    int numResults = (int) (100000 * 0.1);
    int step = 100000 / numResults;
    for (int i = 0; i < 100001 && dimHyperUniqueFilterVals.size() < numResults; i += step) {
      dimHyperUniqueFilterVals.add(String.valueOf(i));
    }

    DimFilter filter = new InDimFilter("dimHyperUnique", dimHyperUniqueFilterVals, null);

    return Druids.newScanQueryBuilder()
                 .filters(filter)
                 .dataSource("blah")
                 .intervals(intervalSpec)
                 .order(ordering);
  }

  private static Druids.ScanQueryBuilder basicC(final GeneratorSchemaInfo basicSchema)
  {
    final QuerySegmentSpec intervalSpec =
        new MultipleIntervalSegmentSpec(Collections.singletonList(basicSchema.getDataInterval()));

    final String dimName = "dimUniform";
    return Druids.newScanQueryBuilder()
                 .filters(new SelectorDimFilter(dimName, "3", StrlenExtractionFn.instance()))
                 .intervals(intervalSpec)
                 .dataSource("blah")
                 .order(ordering);
  }

  private static Druids.ScanQueryBuilder basicD(final GeneratorSchemaInfo basicSchema)
  {
    final QuerySegmentSpec intervalSpec = new MultipleIntervalSegmentSpec(
        Collections.singletonList(basicSchema.getDataInterval())
    );

    final String dimName = "dimUniform";

    return Druids.newScanQueryBuilder()
                 .filters(new BoundDimFilter(dimName, "100", "10000", true, true, true, null, null))
                 .intervals(intervalSpec)
                 .dataSource("blah")
                 .order(ordering);
  }

  /**
   * Setup everything common for benchmarking both the incremental-index and the queriable-index.
   */
  @Setup
  public void setup()
  {
    log.info("SETUP CALLED AT " + +System.currentTimeMillis());

    ComplexMetrics.registerSerde(HyperUniquesSerde.TYPE_NAME, new HyperUniquesSerde());

    setupQueries();

    String[] schemaQuery = schemaAndQuery.split("\\.");
    String schemaName = schemaQuery[0];
    String queryName = schemaQuery[1];

    schemaInfo = GeneratorBasicSchemas.SCHEMA_MAP.get(schemaName);
    queryBuilder = SCHEMA_QUERY_MAP.get(schemaName).get(queryName);
    queryBuilder.limit(limit);
    query = queryBuilder.build();

    generator = new DataGenerator(
        schemaInfo.getColumnSchemas(),
        System.currentTimeMillis(),
        schemaInfo.getDataInterval(),
        rowsPerSegment
    );

    factory = new ScanQueryRunnerFactory(
        new ScanQueryQueryToolChest(DefaultGenericQueryMetricsFactory.instance()),
        new ScanQueryEngine(),
        new ScanQueryConfig()
    );
  }

  /**
   * Setup/teardown everything specific for benchmarking the incremental-index.
   */
  @State(Scope.Benchmark)
  public static class IncrementalIndexState
  {
    @Param({"onheap", "offheap"})
    private String indexType;

    IncrementalIndex incIndex;

    @Setup
    public void setup(ScanBenchmark global) throws JsonProcessingException
    {
      // Creates an AppendableIndexSpec that corresponds to the indexType parametrization.
      // It is used in {@code global.makeIncIndex()} to instanciate an incremental-index of the specified type.
      global.appendableIndexSpec = IncrementalIndexCreator.parseIndexType(indexType);
      incIndex = global.makeIncIndex();
      global.generator.addToIndex(incIndex, global.rowsPerSegment);
    }

    @TearDown
    public void tearDown()
    {
      if (incIndex != null) {
        incIndex.close();
      }
    }
  }

  /**
   * Setup/teardown everything specific for benchmarking the queriable-index.
   */
  @State(Scope.Benchmark)
  public static class QueryableIndexState
  {
    @Param({"2", "4"})
    private int numSegments;

    @Param({"2"})
    private int numProcessingThreads;

    private ExecutorService executorService;
    private File qIndexesDir;
    private List<QueryableIndex> qIndexes;

    @Setup
    public void setup(ScanBenchmark global) throws IOException
    {
      global.appendableIndexSpec = new OnheapIncrementalIndex.Spec();

      executorService = Execs.multiThreaded(numProcessingThreads, "ScanThreadPool");

      qIndexesDir = FileUtils.createTempDir();
      qIndexes = new ArrayList<>();

      for (int i = 0; i < numSegments; i++) {
        log.info("Generating rows for segment " + i);

        IncrementalIndex incIndex = global.makeIncIndex();
        global.generator.reset(RNG_SEED + i).addToIndex(incIndex, global.rowsPerSegment);

        File indexFile = INDEX_MERGER_V9.persist(
            incIndex,
            new File(qIndexesDir, String.valueOf(i)),
            IndexSpec.DEFAULT,
            null
        );
        incIndex.close();

        qIndexes.add(INDEX_IO.loadIndex(indexFile));
      }
    }

    @TearDown
    public void tearDown()
    {
      for (QueryableIndex index : qIndexes) {
        if (index != null) {
          index.close();
        }
      }
      if (qIndexesDir != null) {
        qIndexesDir.delete();
      }
    }
  }

  private IncrementalIndex makeIncIndex()
  {
    return appendableIndexSpec.builder()
        .setSimpleTestingIndexSchema(schemaInfo.getAggsArray())
        .setMaxRowCount(rowsPerSegment)
        .build();
  }

  private static <T> List<T> runQuery(QueryRunnerFactory factory, QueryRunner runner, Query<T> query)
  {
    QueryToolChest toolChest = factory.getToolchest();
    QueryRunner<T> theRunner = new FinalizeResultsQueryRunner<>(
        toolChest.mergeResults(toolChest.preMergeQueryDecoration(runner)),
        toolChest
    );

    Sequence<T> queryResult = theRunner.run(QueryPlus.wrap(query), ResponseContext.createEmpty());
    return queryResult.toList();
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void querySingleIncrementalIndex(Blackhole blackhole, IncrementalIndexState state)
  {
    QueryRunner<ScanResultValue> runner = QueryBenchmarkUtil.makeQueryRunner(
        factory,
        SegmentId.dummy("incIndex"),
        new IncrementalIndexSegment(state.incIndex, SegmentId.dummy("incIndex"))
    );

    Query effectiveQuery = query
        .withDataSource(new TableDataSource("incIndex"))
        .withQuerySegmentSpec(
            new MultipleSpecificSegmentSpec(
                ImmutableList.of(
                    new SegmentDescriptor(
                        Intervals.ETERNITY,
                        "dummy_version",
                        0
                    )
                )
            )
        )
        .withOverriddenContext(
            ImmutableMap.of(ScanQuery.CTX_KEY_OUTERMOST, false)
        );

    List<ScanResultValue> results = ScanBenchmark.runQuery(factory, runner, effectiveQuery);
    blackhole.consume(results);
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void querySingleQueryableIndex(Blackhole blackhole, QueryableIndexState state)
  {
    final QueryRunner<Result<ScanResultValue>> runner = QueryBenchmarkUtil.makeQueryRunner(
        factory,
        SegmentId.dummy("qIndex"),
        new QueryableIndexSegment(state.qIndexes.get(0), SegmentId.dummy("qIndex"))
    );

    Query effectiveQuery = query
        .withDataSource(new TableDataSource("qIndex"))
        .withQuerySegmentSpec(
            new MultipleSpecificSegmentSpec(
                ImmutableList.of(
                    new SegmentDescriptor(
                        Intervals.ETERNITY,
                        "dummy_version",
                        0
                    )
                )
            )
        )
        .withOverriddenContext(
            ImmutableMap.of(ScanQuery.CTX_KEY_OUTERMOST, false)
        );

    List<ScanResultValue> results = ScanBenchmark.runQuery(factory, runner, effectiveQuery);
    blackhole.consume(results);
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void queryMultiQueryableIndex(Blackhole blackhole, QueryableIndexState state)
  {
    List<SegmentDescriptor> segmentDescriptors = new ArrayList<>();
    List<QueryRunner<Row>> runners = new ArrayList<>();
    QueryToolChest toolChest = factory.getToolchest();
    for (int i = 0; i < state.numSegments; i++) {
      String segmentName = "qIndex";
      final QueryRunner<Result<ScanResultValue>> runner = QueryBenchmarkUtil.makeQueryRunner(
          factory,
          SegmentId.dummy(segmentName),
          new QueryableIndexSegment(state.qIndexes.get(i), SegmentId.dummy(segmentName, i))
      );
      segmentDescriptors.add(
          new SegmentDescriptor(
              Intervals.ETERNITY,
              "dummy_version",
              i
          )
      );
      runners.add(toolChest.preMergeQueryDecoration(runner));
    }

    QueryRunner theRunner = toolChest.postMergeQueryDecoration(
        new FinalizeResultsQueryRunner<>(
            toolChest.mergeResults(factory.mergeRunners(state.executorService, runners)),
            toolChest
        )
    );

    Query effectiveQuery = query
        .withDataSource(new TableDataSource("qIndex"))
        .withQuerySegmentSpec(
            new MultipleSpecificSegmentSpec(segmentDescriptors)
        )
        .withOverriddenContext(
            ImmutableMap.of(ScanQuery.CTX_KEY_OUTERMOST, false)
        );

    Sequence<Result<ScanResultValue>> queryResult = theRunner.run(
        QueryPlus.wrap(effectiveQuery),
        ResponseContext.createEmpty()
    );
    List<Result<ScanResultValue>> results = queryResult.toList();
    blackhole.consume(results);
  }
}
