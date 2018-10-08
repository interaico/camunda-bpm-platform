/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.test.standalone.history;

import static org.camunda.bpm.engine.ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang.time.DateUtils;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.batch.history.HistoricBatch;
import org.camunda.bpm.engine.history.HistoricIncident;
import org.camunda.bpm.engine.impl.batch.BatchEntity;
import org.camunda.bpm.engine.impl.batch.BatchSeedJobHandler;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.HistoryLevel;
import org.camunda.bpm.engine.impl.history.event.HistoryEventTypes;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.HistoricIncidentEntity;
import org.camunda.bpm.engine.impl.persistence.entity.JobEntity;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.management.JobDefinition;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.repository.DeploymentWithDefinitions;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.test.api.runtime.FailingDelegate;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class CustomHistoryLevelIncidentTest {

  @Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
      new Object[]{ Arrays.asList(HistoryEventTypes.INCIDENT_CREATE) },
      new Object[]{ Arrays.asList(HistoryEventTypes.INCIDENT_CREATE, HistoryEventTypes.INCIDENT_RESOLVE) },
      new Object[]{ Arrays.asList(HistoryEventTypes.INCIDENT_DELETE, HistoryEventTypes.INCIDENT_CREATE, HistoryEventTypes.INCIDENT_MIGRATE, HistoryEventTypes.INCIDENT_RESOLVE) }
    });
  }

  @Parameter(0)
  public static List<HistoryEventTypes> eventTypes;

  protected HistoryService historyService;
  protected RuntimeService runtimeService;
  protected ManagementService managementService;
  protected RepositoryService repositoryService;
  protected TaskService taskService;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;


  public static String PROCESS_DEFINITION_KEY = "oneFailingServiceTaskProcess";
  public static BpmnModelInstance FAILING_SERVICE_TASK_MODEL  = Bpmn.createExecutableProcess(PROCESS_DEFINITION_KEY)
    .startEvent("start")
    .serviceTask("task")
      .camundaAsyncBefore()
      .camundaClass(FailingDelegate.class.getName())
    .endEvent("end")
    .done();

  @Before
  public void setUp() throws Exception {
    ProcessEngine engine = configureEngine();
    runtimeService = engine.getRuntimeService();
    historyService = engine.getHistoryService();
    managementService = engine.getManagementService();
    repositoryService = engine.getRepositoryService();
    taskService = engine.getTaskService();
  }

  protected ProcessEngine configureEngine() {
    processEngineConfiguration = (ProcessEngineConfigurationImpl)ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration();
    processEngineConfiguration.setJdbcUrl("jdbc:h2:mem:" + getClass().getSimpleName());
    processEngineConfiguration.setDatabaseSchemaUpdate(DB_SCHEMA_UPDATE_CREATE_DROP);

    HistoryLevel customHistoryLevelIncident = new CustomHistoryLevelIncident(eventTypes);
    processEngineConfiguration.setCustomHistoryLevels(Arrays.asList(customHistoryLevelIncident));
    processEngineConfiguration.setHistory("aCustomHistoryLevelIncident");

    return processEngineConfiguration.buildProcessEngine();
  }

  @After
  public void tearDown() throws Exception {
    ClockUtil.setCurrentTime(new Date());
    processEngineConfiguration.setBatchOperationHistoryTimeToLive(null);
    processEngineConfiguration.setBatchOperationsForHistoryCleanup(null);
    for (Batch batch : managementService.createBatchQuery().list()) {
      managementService.deleteBatch(batch.getId(), true);
    }

    // remove history of completed batches
    for (HistoricBatch historicBatch : historyService.createHistoricBatchQuery().list()) {
      historyService.deleteHistoricBatch(historicBatch.getId());
    }

    processEngineConfiguration.getCommandExecutorTxRequired().execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {

        List<Job> jobs = managementService.createJobQuery().list();
        for (Job job : jobs) {
          commandContext.getJobManager().deleteJob((JobEntity) job);
          commandContext.getHistoricJobLogManager().deleteHistoricJobLogByJobId(job.getId());
        }

        List<HistoricIncident> historicIncidents = historyService.createHistoricIncidentQuery().list();
        for (HistoricIncident historicIncident : historicIncidents) {
          commandContext.getDbEntityManager().delete((HistoricIncidentEntity) historicIncident);
        }

        commandContext.getMeterLogManager().deleteAll();

        return null;
      }
    });
    processEngineConfiguration.close();
  }

  @Test
  public void testDeleteHistoricIncidentByProcDefId() {
    // given

    DeploymentWithDefinitions deployment = repositoryService.createDeployment().addModelInstance("process.bpmn", FAILING_SERVICE_TASK_MODEL).deployWithResult();
    String processDefinitionId = deployment.getDeployedProcessDefinitions().get(0).getId();

    runtimeService.startProcessInstanceById(processDefinitionId);
    executeAvailableJobs();

    if (eventTypes != null) {
      HistoricIncident historicIncident = historyService.createHistoricIncidentQuery().singleResult();
      assertNotNull(historicIncident);
    }

    // when
    repositoryService.deleteProcessDefinitions()
      .byKey(PROCESS_DEFINITION_KEY)
      .cascade()
      .delete();

    // then
    List<HistoricIncident> incidents = historyService.createHistoricIncidentQuery().list();
    assertEquals(0, incidents.size());
  }

  @Test
  public void testDeleteHistoricIncidentByBatchId() {
    // given
    initBatchOperationHistoryTimeToLive();
    ClockUtil.setCurrentTime(DateUtils.addDays(new Date(), -11));

    BatchEntity batch = (BatchEntity) createFailingMigrationBatch();

    try {
      executeBatch(batch);
      fail("Expected exception");
    } catch (ProcessEngineException e) {
      // do nothing
    }

    ClockUtil.setCurrentTime(DateUtils.addDays(new Date(), -10));
    managementService.deleteBatch(batch.getId(), false);
    ClockUtil.setCurrentTime(new Date());

    // assume
    if (eventTypes != null) {
      HistoricIncident historicIncident = historyService.createHistoricIncidentQuery().singleResult();
      assertNotNull(historicIncident);
    }

    // when trigger history cleanup
    historyService.cleanUpHistoryAsync(true);
    for (Job job : historyService.findHistoryCleanupJobs()) {
      managementService.executeJob(job.getId());
    }

    // then
    List<HistoricIncident> incidents = historyService.createHistoricIncidentQuery().list();
    assertEquals(0, incidents.size());
  }

  @Test
  public void testDeleteHistoricIncidentByJobDefinitionId() {
    // given
    BatchEntity batch = (BatchEntity) createFailingMigrationBatch();

    try {
      executeBatch(batch);
      fail("Expected exception");
    } catch (ProcessEngineException e) {
      // do nothing
    }

    // assume
    if (eventTypes != null) {
      HistoricIncident historicIncident = historyService.createHistoricIncidentQuery().singleResult();
      assertNotNull(historicIncident);
    }

    // when
    managementService.deleteBatch(batch.getId(), true);

    // then
    List<HistoricIncident> incidents = historyService.createHistoricIncidentQuery().list();
    assertEquals(0, incidents.size());
  }

  protected void executeAvailableJobs() {
    List<Job> jobs = managementService.createJobQuery().withRetriesLeft().list();

    if (jobs.isEmpty()) {
      return;
    }

    for (Job job : jobs) {
      try {
        managementService.executeJob(job.getId());
      } catch (Exception e) {}
    }

    executeAvailableJobs();
  }
  protected void executeBatch(BatchEntity batch) {
    executeSeedBatchJob(batch);

    List<Job> list = managementService.createJobQuery().list();
    for (Job job : list) {
      if (((JobEntity) job).getJobHandlerType().equals("instance-migration")) {
        managementService.setJobRetries(job.getId(), 1);
      }
    }

    executeBatchJobs(batch);
  }

  protected void executeBatchJobs(BatchEntity batch) {
    JobDefinition executionJobDefinition = managementService
      .createJobDefinitionQuery()
      .jobDefinitionId(batch.getBatchJobDefinitionId())
      .jobType(Batch.TYPE_PROCESS_INSTANCE_MIGRATION).singleResult();
    List<Job> executionJobs = managementService
      .createJobQuery()
      .jobDefinitionId(executionJobDefinition.getId())
      .list();
    for (Job job : executionJobs) {
      managementService.executeJob(job.getId());
    }
  }

  protected void executeSeedBatchJob(BatchEntity batch) {
    JobDefinition definition = managementService
      .createJobDefinitionQuery()
      .jobDefinitionId(batch.getSeedJobDefinitionId())
      .jobType(BatchSeedJobHandler.TYPE)
      .singleResult();
    Job batchJob = managementService
        .createJobQuery()
        .jobDefinitionId(definition.getId())
        .singleResult();
    managementService.executeJob(batchJob.getId());
  }


  protected void initBatchOperationHistoryTimeToLive() {
    processEngineConfiguration.setBatchOperationHistoryTimeToLive("P0D");
    processEngineConfiguration.initHistoryCleanup();
  }

  protected Batch createFailingMigrationBatch() {
    BpmnModelInstance instance = createModelInstance();

    ProcessDefinition sourceProcessDefinition = repositoryService.createDeployment().addModelInstance("process.bpmn", instance).deployWithResult().getDeployedProcessDefinitions().get(0);
    ProcessDefinition targetProcessDefinition = repositoryService.createDeployment().addModelInstance("process.bpmn", instance).deployWithResult().getDeployedProcessDefinitions().get(0);

    MigrationPlan migrationPlan = runtimeService
        .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapEqualActivities()
        .build();

    ProcessInstance processInstance = runtimeService.startProcessInstanceById(sourceProcessDefinition.getId());

    Batch batch = runtimeService.newMigration(migrationPlan).processInstanceIds(Arrays.asList(processInstance.getId(), "unknownId")).executeAsync();
    return batch;
  }

  protected BpmnModelInstance createModelInstance() {
    BpmnModelInstance instance = Bpmn.createExecutableProcess("process")
        .startEvent("start")
        .userTask("userTask1")
        .endEvent("end")
        .done();
    return instance;
  }
}