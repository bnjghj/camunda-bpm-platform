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
package org.camunda.bpm.engine.test.api.history;

import static org.camunda.bpm.engine.test.api.runtime.migration.ModifiableBpmnModelInstance.modify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.DecisionService;
import org.camunda.bpm.engine.EntityTypes;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.batch.history.HistoricBatch;
import org.camunda.bpm.engine.history.HistoricDecisionInstance;
import org.camunda.bpm.engine.history.HistoricDecisionInstanceQuery;
import org.camunda.bpm.engine.history.UserOperationLogEntry;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.api.runtime.migration.MigrationTestRule;
import org.camunda.bpm.engine.test.api.runtime.migration.batch.BatchMigrationHelper;
import org.camunda.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.Variables;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class BatchHistoricDecisionInstanceDeletionUserOperationTest {

  protected static String DECISION = "decision";

  public static final String USER_ID = "userId";

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  protected DecisionService decisionService;
  protected HistoryService historyService;
  protected ManagementService managementService;
  protected IdentityService identityService;

  protected List<String> decisionInstanceIds;

  @Before
  public void setup() {
    historyService = engineRule.getHistoryService();
    decisionService = engineRule.getDecisionService();
    managementService = engineRule.getManagementService();
    identityService = engineRule.getIdentityService();
    decisionInstanceIds = new ArrayList<String>();
  }

  @Before
  public void evaluateDecisionInstances() {
    testRule.deploy("org/camunda/bpm/engine/test/api/dmn/Example.dmn");

    VariableMap variables = Variables.createVariables()
        .putValue("status", "silver")
        .putValue("sum", 723);

    for (int i = 0; i < 10; i++) {
      decisionService.evaluateDecisionByKey(DECISION).variables(variables).evaluate();
    }

    List<HistoricDecisionInstance> decisionInstances = historyService.createHistoricDecisionInstanceQuery().list();
    for(HistoricDecisionInstance decisionInstance : decisionInstances) {
      decisionInstanceIds.add(decisionInstance.getId());
    }
  }

  @After
  public void removeBatches() {
    for (Batch batch : managementService.createBatchQuery().list()) {
      managementService.deleteBatch(batch.getId(), true);
    }

    // remove history of completed batches
    for (HistoricBatch historicBatch : historyService.createHistoricBatchQuery().list()) {
      historyService.deleteHistoricBatch(historicBatch.getId());
    }
  }

  @After
  public void clearAuthentication() {
    identityService.clearAuthentication();
  }

  @Test
  public void testCreationByIds() {
    // when
    identityService.setAuthenticatedUserId(USER_ID);
    historyService.deleteHistoricDecisionInstancesAsync(decisionInstanceIds);
    identityService.clearAuthentication();

    // then
    List<UserOperationLogEntry> opLogEntries = engineRule.getHistoryService().createUserOperationLogQuery().list();
    Assert.assertEquals(3, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);

    UserOperationLogEntry typeEntry = entries.get("type");
    assertNotNull(typeEntry);
    assertEquals(EntityTypes.DECISION_INSTANCE, typeEntry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_DELETE, typeEntry.getOperationType());
    assertNull(typeEntry.getProcessDefinitionId());
    assertNull(typeEntry.getProcessDefinitionKey());
    assertNull(typeEntry.getProcessInstanceId());
    assertNull(typeEntry.getOrgValue());
    assertEquals("history", typeEntry.getNewValue());

    UserOperationLogEntry asyncEntry = entries.get("async");
    assertNotNull(asyncEntry);
    assertEquals(EntityTypes.DECISION_INSTANCE, typeEntry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_DELETE, typeEntry.getOperationType());
    assertNull(typeEntry.getProcessDefinitionId());
    assertNull(typeEntry.getProcessDefinitionKey());
    assertNull(typeEntry.getProcessInstanceId());
    assertNull(typeEntry.getOrgValue());
    assertEquals("true", asyncEntry.getNewValue());

    UserOperationLogEntry numInstancesEntry = entries.get("nrOfInstances");
    assertNotNull(numInstancesEntry);
    assertEquals(EntityTypes.DECISION_INSTANCE, typeEntry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_DELETE, typeEntry.getOperationType());
    assertNull(typeEntry.getProcessDefinitionId());
    assertNull(typeEntry.getProcessDefinitionKey());
    assertNull(typeEntry.getProcessInstanceId());
    assertNull(typeEntry.getOrgValue());
    assertEquals("10", numInstancesEntry.getNewValue());

    assertEquals(typeEntry.getOperationId(), asyncEntry.getOperationId());
    assertEquals(asyncEntry.getOperationId(), numInstancesEntry.getOperationId());
  }

  @Test
  public void testCreationByQuery() {
    // given
    HistoricDecisionInstanceQuery query = historyService.createHistoricDecisionInstanceQuery().decisionDefinitionKey(DECISION);

    // when
    identityService.setAuthenticatedUserId(USER_ID);
    historyService.deleteHistoricDecisionInstancesAsync(query);
    identityService.clearAuthentication();

    // then
    List<UserOperationLogEntry> opLogEntries = engineRule.getHistoryService().createUserOperationLogQuery().list();
    Assert.assertEquals(3, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);

    UserOperationLogEntry typeEntry = entries.get("type");
    assertNotNull(typeEntry);
    assertEquals(EntityTypes.DECISION_INSTANCE, typeEntry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_DELETE, typeEntry.getOperationType());
    assertNull(typeEntry.getProcessDefinitionId());
    assertNull(typeEntry.getProcessDefinitionKey());
    assertNull(typeEntry.getProcessInstanceId());
    assertNull(typeEntry.getOrgValue());
    assertEquals("history", typeEntry.getNewValue());

    UserOperationLogEntry asyncEntry = entries.get("async");
    assertNotNull(asyncEntry);
    assertEquals(EntityTypes.DECISION_INSTANCE, typeEntry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_DELETE, typeEntry.getOperationType());
    assertNull(typeEntry.getProcessDefinitionId());
    assertNull(typeEntry.getProcessDefinitionKey());
    assertNull(typeEntry.getProcessInstanceId());
    assertNull(typeEntry.getOrgValue());
    assertEquals("true", asyncEntry.getNewValue());

    UserOperationLogEntry numInstancesEntry = entries.get("nrOfInstances");
    assertNotNull(numInstancesEntry);
    assertEquals(EntityTypes.DECISION_INSTANCE, typeEntry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_DELETE, typeEntry.getOperationType());
    assertNull(typeEntry.getProcessDefinitionId());
    assertNull(typeEntry.getProcessDefinitionKey());
    assertNull(typeEntry.getProcessInstanceId());
    assertNull(typeEntry.getOrgValue());
    assertEquals("10", numInstancesEntry.getNewValue());

    assertEquals(typeEntry.getOperationId(), asyncEntry.getOperationId());
    assertEquals(asyncEntry.getOperationId(), numInstancesEntry.getOperationId());
  }

  @Test
  public void testCreationByIdsAndQuery() {
    // given
    HistoricDecisionInstanceQuery query = historyService.createHistoricDecisionInstanceQuery().decisionDefinitionKey(DECISION);

    // when
    identityService.setAuthenticatedUserId(USER_ID);
    historyService.deleteHistoricDecisionInstancesAsync(decisionInstanceIds, query);
    identityService.clearAuthentication();

    // then
    List<UserOperationLogEntry> opLogEntries = engineRule.getHistoryService().createUserOperationLogQuery().list();
    Assert.assertEquals(3, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);

    UserOperationLogEntry typeEntry = entries.get("type");
    assertNotNull(typeEntry);
    assertEquals(EntityTypes.DECISION_INSTANCE, typeEntry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_DELETE, typeEntry.getOperationType());
    assertNull(typeEntry.getProcessDefinitionId());
    assertNull(typeEntry.getProcessDefinitionKey());
    assertNull(typeEntry.getProcessInstanceId());
    assertNull(typeEntry.getOrgValue());
    assertEquals("history", typeEntry.getNewValue());

    UserOperationLogEntry asyncEntry = entries.get("async");
    assertNotNull(asyncEntry);
    assertEquals(EntityTypes.DECISION_INSTANCE, typeEntry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_DELETE, typeEntry.getOperationType());
    assertNull(typeEntry.getProcessDefinitionId());
    assertNull(typeEntry.getProcessDefinitionKey());
    assertNull(typeEntry.getProcessInstanceId());
    assertNull(typeEntry.getOrgValue());
    assertEquals("true", asyncEntry.getNewValue());

    UserOperationLogEntry numInstancesEntry = entries.get("nrOfInstances");
    assertNotNull(numInstancesEntry);
    assertEquals(EntityTypes.DECISION_INSTANCE, typeEntry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_DELETE, typeEntry.getOperationType());
    assertNull(typeEntry.getProcessDefinitionId());
    assertNull(typeEntry.getProcessDefinitionKey());
    assertNull(typeEntry.getProcessInstanceId());
    assertNull(typeEntry.getOrgValue());
    assertEquals("10", numInstancesEntry.getNewValue());

    assertEquals(typeEntry.getOperationId(), asyncEntry.getOperationId());
    assertEquals(asyncEntry.getOperationId(), numInstancesEntry.getOperationId());
  }

  @Test
  public void testNoCreationOnSyncBatchJobExecution() {
    // given
    Batch batch = historyService.deleteHistoricDecisionInstancesAsync(decisionInstanceIds);

    // when
    engineRule.getIdentityService().setAuthenticatedUserId(USER_ID);
    executeJobs(batch);
    engineRule.getIdentityService().clearAuthentication();

    // then
    assertEquals(0, engineRule.getHistoryService().createUserOperationLogQuery().count());
  }

  @Test
  public void testNoCreationOnSyncBatchJobExecutionByIds() {
    // given
    HistoricDecisionInstanceQuery query = historyService.createHistoricDecisionInstanceQuery().decisionDefinitionKey(DECISION);
    Batch batch = historyService.deleteHistoricDecisionInstancesAsync(query);

    // when
    engineRule.getIdentityService().setAuthenticatedUserId(USER_ID);
    executeJobs(batch);
    engineRule.getIdentityService().clearAuthentication();

    // then
    assertEquals(0, engineRule.getHistoryService().createUserOperationLogQuery().count());
  }

  @Test
  public void testNoCreationOnSyncBatchJobExecutionByIdsAndQuery() {
    // given
    HistoricDecisionInstanceQuery query = historyService.createHistoricDecisionInstanceQuery().decisionDefinitionKey(DECISION);
    Batch batch = historyService.deleteHistoricDecisionInstancesAsync(decisionInstanceIds, query);

    // when
    engineRule.getIdentityService().setAuthenticatedUserId(USER_ID);
    executeJobs(batch);
    engineRule.getIdentityService().clearAuthentication();

    // then
    assertEquals(0, engineRule.getHistoryService().createUserOperationLogQuery().count());
  }

  @Test
  public void testNoCreationOnJobExecutorBatchJobExecutionByIds() {
    // given
    // given
    historyService.deleteHistoricDecisionInstancesAsync(decisionInstanceIds);

    // when
    testRule.waitForJobExecutorToProcessAllJobs(5000L);

    // then
    assertEquals(0, engineRule.getHistoryService().createUserOperationLogQuery().count());
  }

  @Test
  public void testNoCreationOnJobExecutorBatchJobExecutionByQuery() {
    // given
    // given
    HistoricDecisionInstanceQuery query = historyService.createHistoricDecisionInstanceQuery().decisionDefinitionKey(DECISION);
    historyService.deleteHistoricDecisionInstancesAsync(query);

    // when
    testRule.waitForJobExecutorToProcessAllJobs(5000L);

    // then
    assertEquals(0, engineRule.getHistoryService().createUserOperationLogQuery().count());
  }

  @Test
  public void testNoCreationOnJobExecutorBatchJobExecutionByIdsAndQuery() {
    // given
    // given
    HistoricDecisionInstanceQuery query = historyService.createHistoricDecisionInstanceQuery().decisionDefinitionKey(DECISION);
    historyService.deleteHistoricDecisionInstancesAsync(decisionInstanceIds, query);

    // when
    testRule.waitForJobExecutorToProcessAllJobs(5000L);

    // then
    assertEquals(0, engineRule.getHistoryService().createUserOperationLogQuery().count());
  }

  protected Map<String, UserOperationLogEntry> asMap(List<UserOperationLogEntry> logEntries) {
    Map<String, UserOperationLogEntry> map = new HashMap<String, UserOperationLogEntry>();

    for (UserOperationLogEntry entry : logEntries) {

      UserOperationLogEntry previousValue = map.put(entry.getProperty(), entry);
      if (previousValue != null) {
        fail("expected only entry for every property");
      }
    }

    return map;
  }

  protected void executeJobs(Batch batch) {
    Job job = managementService.createJobQuery().jobDefinitionId(batch.getSeedJobDefinitionId()).singleResult();

    // seed job
    managementService.executeJob(job.getId());

    for (Job pending : managementService.createJobQuery().jobDefinitionId(batch.getBatchJobDefinitionId()).list()) {
      managementService.executeJob(pending.getId());
    }
  }

}