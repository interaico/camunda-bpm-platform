/*
 * Copyright © 2013-2018 camunda services GmbH and various authors (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package org.camunda.bpm.engine.impl.cmd;

import org.camunda.bpm.engine.history.UserOperationLogEntry;
import org.camunda.bpm.engine.impl.batch.BatchEntity;
import org.camunda.bpm.engine.impl.cfg.CommandChecker;
import org.camunda.bpm.engine.impl.management.UpdateJobDefinitionSuspensionStateBuilderImpl;
import org.camunda.bpm.engine.impl.persistence.entity.SuspensionState;

public class SuspendBatchCmd extends AbstractSetBatchStateCmd {

  public SuspendBatchCmd(String batchId) {
    super(batchId);
  }

  protected SuspensionState getNewSuspensionState() {
    return SuspensionState.SUSPENDED;
  }

  protected void checkAccess(CommandChecker checker, BatchEntity batch) {
    checker.checkSuspendBatch(batch);
  }

  protected AbstractSetJobDefinitionStateCmd createSetJobDefinitionStateCommand(UpdateJobDefinitionSuspensionStateBuilderImpl builder) {
    return new SuspendJobDefinitionCmd(builder);
  }

  protected String getUserOperationType() {
    return UserOperationLogEntry.OPERATION_TYPE_SUSPEND_BATCH;
  }

}
