/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.bigquery;

import static org.junit.Assert.assertEquals;

import com.google.api.services.bigquery.model.QueryResponse;
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.VoidCoder;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.io.gcp.testing.BigqueryClient;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestPipelineOptions;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.values.PCollection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration test for BigqueryIO with DataflowRunner and DirectRunner. */
@RunWith(JUnit4.class)
public class BigQueryToTableIT {

  private BigQueryToTableOptions options;
  private String project;
  private final String timestamp = Long.toString(System.currentTimeMillis());
  private final String bigQueryDatasetId = "bq_query_to_table_" + timestamp;
  private final String outputTableName = "output_table";
  private BigQueryOptions bqOption;
  private String outputTable;
  private BigqueryClient bqClient;

  /** Customized PipelineOption for BigQueryToTable Pipeline. */
  public interface BigQueryToTableOptions extends TestPipelineOptions {

    @Description("The BigQuery query to be used for creating the source")
    @Validation.Required
    String getQuery();

    void setQuery(String query);

    @Description(
        "BigQuery table to write to, specified as "
            + "<project_id>:<dataset_id>.<table_id>. The dataset must already exist.")
    @Validation.Required
    String getOutput();

    void setOutput(String value);

    @Description("BigQuery output table schema.")
    @Validation.Required
    TableSchema getOutputSchema();

    void setOutputSchema(TableSchema value);

    @Description("Whether to force reshuffle.")
    @Default.Boolean(false)
    boolean getReshuffle();

    void setReshuffle(boolean reshuffle);

    @Description("Whether to use the Standard SQL dialect when querying BigQuery.")
    @Default.Boolean(false)
    boolean getUsingStandardSql();

    void setUsingStandardSql(boolean usingStandardSql);
  }

  @Before
  public void setupBqEnvironment() {
    PipelineOptionsFactory.register(BigQueryToTableOptions.class);
    options = TestPipeline.testingPipelineOptions().as(BigQueryToTableOptions.class);
    options.setTempLocation(options.getTempRoot() + "/bq_it_temp");
    project = TestPipeline.testingPipelineOptions().as(GcpOptions.class).getProject();

    bqOption = options.as(BigQueryOptions.class);
    bqClient = new BigqueryClient(bqOption.getAppName());
    bqClient.createNewDataset(project, bigQueryDatasetId);
    outputTable = project + ":" + bigQueryDatasetId + "." + outputTableName;
  }

  @After
  public void cleanBqEnvironment() {
    bqClient.deleteDataset(project, bigQueryDatasetId);
  }

  private void runBigQueryToTablePipeline() {
    Pipeline p = Pipeline.create(options);
    BigQueryIO.Read bigQueryRead = BigQueryIO.read().fromQuery(options.getQuery());
    if (options.getUsingStandardSql()) {
      bigQueryRead = bigQueryRead.usingStandardSql();
    }
    PCollection<TableRow> input = p.apply(bigQueryRead);
    if (options.getReshuffle()) {
      input =
          input
              .apply(WithKeys.<Void, TableRow>of((Void) null))
              .setCoder(KvCoder.of(VoidCoder.of(), TableRowJsonCoder.of()))
              .apply(Reshuffle.<Void, TableRow>of())
              .apply(Values.<TableRow>create());
    }
    input.apply(
        BigQueryIO.writeTableRows()
            .to(options.getOutput())
            .withSchema(options.getOutputSchema())
            .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));

    p.run().waitUntilFinish();
  }

  @Test
  public void testLegacyQueryWithoutReshuffle() throws Exception {
    List<String> expectedList = Arrays.asList("apple", "orange");
    options.setQuery("SELECT * FROM (SELECT \"apple\" as fruit), (SELECT \"orange\" as fruit),");
    options.setOutput(outputTable);
    List<TableFieldSchema> fieldSchemas = new ArrayList<>();
    fieldSchemas.add(new TableFieldSchema().setName("fruit").setType("STRING"));
    options.setOutputSchema(new TableSchema().setFields(fieldSchemas));

    runBigQueryToTablePipeline();

    QueryResponse response =
        bqClient.queryWithRetries(String.format("SELECT fruit from [%s];", outputTable), project);
    List<String> tableResult =
        response
            .getRows()
            .stream()
            .flatMap(row -> row.getF().stream().map(cell -> cell.getV().toString()))
            .collect(Collectors.toList());

    assertEquals(expectedList, tableResult);
  }
}
