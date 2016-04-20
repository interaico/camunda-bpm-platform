/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.camunda.bpm.engine.test.api.mgmt;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.batch.BatchStatistics;
import org.camunda.bpm.engine.batch.history.HistoricBatch;
import org.camunda.bpm.engine.history.HistoricIncident;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.HistoricIncidentEntity;
import org.camunda.bpm.engine.impl.persistence.entity.HistoricIncidentManager;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.test.api.runtime.migration.MigrationTestRule;
import org.camunda.bpm.engine.test.api.runtime.migration.batch.BatchMigrationHelper;
import org.camunda.bpm.engine.test.util.CachedProcessEngineRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public class BatchStatisticsQueryTest {

  protected ProcessEngineRule engineRule = new CachedProcessEngineRule();
  protected MigrationTestRule migrationRule = new MigrationTestRule(engineRule);
  protected BatchMigrationHelper helper = new BatchMigrationHelper(engineRule, migrationRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(migrationRule);

  protected ManagementService managementService;

  @Before
  public void initServices() {
    managementService = engineRule.getManagementService();
  }

  @After
  public void removeBatches() {
    for (Batch batch : managementService.createBatchQuery().list()) {
      managementService.deleteBatch(batch.getId(), true);
    }

    // remove history of completed batches
    final HistoryService historyService = engineRule.getHistoryService();
    for (HistoricBatch historicBatch : historyService.createHistoricBatchQuery().list()) {
      historyService.deleteHistoricBatch(historicBatch.getId());
    }

    // delete historic incidents (workaround for CAM-5848)
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engineRule.getProcessEngine().getProcessEngineConfiguration();
    configuration.getCommandExecutorTxRequired().execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {
        HistoricIncidentManager historicIncidentManager = commandContext.getHistoricIncidentManager();
        for (HistoricIncident historicIncident : historyService.createHistoricIncidentQuery().list()) {
          historicIncidentManager
            .delete((HistoricIncidentEntity) historicIncident);
        }
        return null;
      }
    });
  }

  @Test
  public void testQuery() {
    List<BatchStatistics> statistics = managementService.createBatchStatisticsQuery().list();
    assertEquals(0, statistics.size());

    Batch batch1 = helper.createMigrationBatchWithSize(1);

    statistics = managementService.createBatchStatisticsQuery().list();
    assertEquals(1, statistics.size());
    assertEquals(batch1.getId(), statistics.get(0).getId());

    Batch batch2 = helper.createMigrationBatchWithSize(1);
    Batch batch3 = helper.createMigrationBatchWithSize(1);

    statistics = managementService.createBatchStatisticsQuery().list();
    assertEquals(3, statistics.size());

    helper.completeBatch(batch1);
    helper.completeBatch(batch3);

    statistics = managementService.createBatchStatisticsQuery().list();
    assertEquals(1, statistics.size());
    assertEquals(batch2.getId(), statistics.get(0).getId());

    helper.completeBatch(batch2);

    statistics = managementService.createBatchStatisticsQuery().list();
    assertEquals(0, statistics.size());
  }

  @Test
  public void testQueryCount() {
    long count = managementService.createBatchStatisticsQuery().count();
    assertEquals(0, count);

    Batch batch1 = helper.createMigrationBatchWithSize(1);

    count = managementService.createBatchStatisticsQuery().count();
    assertEquals(1, count);

    Batch batch2 = helper.createMigrationBatchWithSize(1);
    Batch batch3 = helper.createMigrationBatchWithSize(1);

    count = managementService.createBatchStatisticsQuery().count();
    assertEquals(3, count);

    helper.completeBatch(batch1);
    helper.completeBatch(batch3);

    count = managementService.createBatchStatisticsQuery().count();
    assertEquals(1, count);

    helper.completeBatch(batch2);

    count = managementService.createBatchStatisticsQuery().count();
    assertEquals(0, count);
  }

  @Test
  public void testStatisticsNoExecutionJobsGenerated() {
    // given
    helper.createMigrationBatchWithSize(3);

    // when
    BatchStatistics batchStatistics = managementService.createBatchStatisticsQuery()
      .singleResult();

    // then
    assertEquals(3, batchStatistics.getSize());
    assertEquals(0, batchStatistics.getJobsCreated());
    assertEquals(3, batchStatistics.getRemainingJobs());
    assertEquals(0, batchStatistics.getCompletedJobs());
    assertEquals(0, batchStatistics.getFailedJobs());
  }

  @Test
  public void testStatisticsMostExecutionJobsGenerated() {
    // given
    Batch batch = helper.createMigrationBatchWithSize(13);
    helper.executeJob(helper.getSeedJob(batch));

    // when
    BatchStatistics batchStatistics = managementService.createBatchStatisticsQuery()
      .singleResult();

    // then
    assertEquals(13, batchStatistics.getSize());
    assertEquals(10, batchStatistics.getJobsCreated());
    assertEquals(13, batchStatistics.getRemainingJobs());
    assertEquals(0, batchStatistics.getCompletedJobs());
    assertEquals(0, batchStatistics.getFailedJobs());
  }

  @Test
  public void testStatisticsAllExecutionJobsGenerated() {
    // given
    Batch batch = helper.createMigrationBatchWithSize(3);
    helper.completeSeedJobs(batch);

    // when
    BatchStatistics batchStatistics = managementService.createBatchStatisticsQuery()
      .singleResult();

    // then
    assertEquals(3, batchStatistics.getSize());
    assertEquals(3, batchStatistics.getJobsCreated());
    assertEquals(3, batchStatistics.getRemainingJobs());
    assertEquals(0, batchStatistics.getCompletedJobs());
    assertEquals(0, batchStatistics.getFailedJobs());
  }

  @Test
  public void testStatisticsOneCompletedJob() {
    // given
    Batch batch = helper.createMigrationBatchWithSize(3);
    helper.completeSeedJobs(batch);
    helper.completeMigrationJobs(batch, 1);

    // when
    BatchStatistics batchStatistics = managementService.createBatchStatisticsQuery()
      .singleResult();

    // then no failed jobs exist
    assertEquals(3, batchStatistics.getSize());
    assertEquals(3, batchStatistics.getJobsCreated());
    assertEquals(2, batchStatistics.getRemainingJobs());
    assertEquals(1, batchStatistics.getCompletedJobs());
    assertEquals(0, batchStatistics.getFailedJobs());
  }

  @Test
  public void testStatisticsOneFailedJob() {
    // given
    Batch batch = helper.createMigrationBatchWithSize(3);
    helper.completeSeedJobs(batch);
    helper.failMigrationJobs(batch, 1);

    // when
    BatchStatistics batchStatistics = managementService.createBatchStatisticsQuery()
      .singleResult();

    // then
    assertEquals(3, batchStatistics.getSize());
    assertEquals(3, batchStatistics.getJobsCreated());
    assertEquals(3, batchStatistics.getRemainingJobs());
    assertEquals(0, batchStatistics.getCompletedJobs());
    assertEquals(1, batchStatistics.getFailedJobs());
  }

  @Test
  public void testStatisticsOneCompletedAndOneFailedJob() {
    // given
    Batch batch = helper.createMigrationBatchWithSize(3);
    helper.completeSeedJobs(batch);
    helper.completeMigrationJobs(batch, 1);
    helper.failMigrationJobs(batch, 1);

    // when
    BatchStatistics batchStatistics = managementService.createBatchStatisticsQuery()
      .singleResult();

    // then
    assertEquals(3, batchStatistics.getSize());
    assertEquals(3, batchStatistics.getJobsCreated());
    assertEquals(2, batchStatistics.getRemainingJobs());
    assertEquals(1, batchStatistics.getCompletedJobs());
    assertEquals(1, batchStatistics.getFailedJobs());
  }

  @Test
  public void testStatisticsWithNotAllGeneratedAndAlreadyCompletedAndFailedJobs() {
    // given
    Batch batch = helper.createMigrationBatchWithSize(13);
    helper.executeJob(helper.getSeedJob(batch));
    helper.completeMigrationJobs(batch, 2);
    helper.failMigrationJobs(batch, 2);

    // when
    BatchStatistics batchStatistics = managementService.createBatchStatisticsQuery()
      .singleResult();

    // then
    assertEquals(13, batchStatistics.getSize());
    assertEquals(10, batchStatistics.getJobsCreated());
    assertEquals(11, batchStatistics.getRemainingJobs());
    assertEquals(2, batchStatistics.getCompletedJobs());
    assertEquals(2, batchStatistics.getFailedJobs());
  }

  @Test
  public void testMultipleBatchesStatistics() {
    // given
    Batch batch1 = helper.createMigrationBatchWithSize(3);
    Batch batch2 = helper.createMigrationBatchWithSize(13);
    Batch batch3 = helper.createMigrationBatchWithSize(4);

    helper.executeJob(helper.getSeedJob(batch2));
    helper.completeMigrationJobs(batch2, 2);
    helper.failMigrationJobs(batch2, 3);

    helper.executeJob(helper.getSeedJob(batch3));
    helper.failMigrationJobs(batch3, 4);

    // when
    List<BatchStatistics> batchStatisticsList = managementService.createBatchStatisticsQuery()
      .list();

    // then
    for (BatchStatistics batchStatistics : batchStatisticsList) {
      if (batch1.getId().equals(batchStatistics.getId())) {
        // batch 1
        assertEquals(3, batchStatistics.getSize());
        assertEquals(0, batchStatistics.getJobsCreated());
        assertEquals(3, batchStatistics.getRemainingJobs());
        assertEquals(0, batchStatistics.getCompletedJobs());
        assertEquals(0, batchStatistics.getFailedJobs());
      }
      else if (batch2.getId().equals(batchStatistics.getId())) {
        // batch 2
        assertEquals(13, batchStatistics.getSize());
        assertEquals(10, batchStatistics.getJobsCreated());
        assertEquals(11, batchStatistics.getRemainingJobs());
        assertEquals(2, batchStatistics.getCompletedJobs());
        assertEquals(3, batchStatistics.getFailedJobs());
      }
      else if (batch3.getId().equals(batchStatistics.getId())) {
        // batch 3
        assertEquals(4, batchStatistics.getSize());
        assertEquals(4, batchStatistics.getJobsCreated());
        assertEquals(4, batchStatistics.getRemainingJobs());
        assertEquals(0, batchStatistics.getCompletedJobs());
        assertEquals(4, batchStatistics.getFailedJobs());
      }
    }
  }

}