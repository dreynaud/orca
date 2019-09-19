/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.rollback

import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.orca.clouddriver.pipeline.providers.aws.ApplySourceServerGroupCapacityStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.providers.aws.CaptureSourceServerGroupCapacityStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.DisableServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.EnableServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.ResizeServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.strategies.Capacity
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.TargetServerGroup
import com.netflix.spinnaker.orca.clouddriver.utils.OortHelper
import com.netflix.spinnaker.orca.kato.pipeline.support.ResizeStrategy
import com.netflix.spinnaker.orca.pipeline.WaitStage
import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.orca.pipeline.model.SyntheticStageOwner
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import sun.reflect.generics.visitor.Reifier

import javax.annotation.Nullable

import static com.netflix.spinnaker.orca.pipeline.StageDefinitionBuilder.newStage

@Slf4j
class ExplicitRollback implements Rollback {
  String rollbackServerGroupName
  String restoreServerGroupName
  Integer targetHealthyRollbackPercentage
  Integer delayBeforeDisableSeconds
  Boolean disableOnly
  Boolean enableAndDisableOnly

  @Autowired
  @JsonIgnore
  EnableServerGroupStage enableServerGroupStage

  @Autowired
  @JsonIgnore
  DisableServerGroupStage disableServerGroupStage

  @Autowired
  @JsonIgnore
  ResizeServerGroupStage resizeServerGroupStage

  @Autowired
  @JsonIgnore
  CaptureSourceServerGroupCapacityStage captureSourceServerGroupCapacityStage

  @Autowired
  @JsonIgnore
  ApplySourceServerGroupCapacityStage applySourceServerGroupCapacityStage

  @Autowired
  @JsonIgnore
  WaitStage waitStage

  @Autowired
  @JsonIgnore
  OortHelper oortHelper

  @JsonIgnore
  @Override
  List<Stage> buildStages(Stage parentStage) {
    Map disableServerGroupContext = new HashMap(parentStage.context)
    disableServerGroupContext.serverGroupName = rollbackServerGroupName
    def disableServerGroupStage = newStage(
      parentStage.execution, disableServerGroupStage.type, "disable", disableServerGroupContext, parentStage, SyntheticStageOwner.STAGE_AFTER
    )

    if (disableOnly) {
      // no need to do anything but disable the newly deployed (and failing!) server group
      return [
        disableServerGroupStage
      ]
    }

    Map enableServerGroupContext = new HashMap(parentStage.context)
    enableServerGroupContext.targetHealthyDeployPercentage = targetHealthyRollbackPercentage
    enableServerGroupContext.serverGroupName = restoreServerGroupName
    def enableServerGroupStage = newStage(
      parentStage.execution, enableServerGroupStage.type, "enable", enableServerGroupContext, parentStage, SyntheticStageOwner.STAGE_AFTER
    )

    if (enableAndDisableOnly) {
      // ensure previous server group is 100% enabled before disabling the new server group
      return [
        enableServerGroupStage,
        disableServerGroupStage
      ]
    }

    def stages = []

    // TODO: dreynaud: can make the snapshot and apply optional if we're not going to resize
    // if the capacity has been previously captured (e.g. as part of a failed deploy), no need to do it again!
    if (!parentStage.getContext().containsKey("sourceServerGroupCapacitySnapshot")) {
      stages << buildCaptureSourceServerGroupCapacityStage(parentStage, parentStage.mapTo(ResizeStrategy.Source))
    }

    stages << enableServerGroupStage

    def resizeStage = buildResizeStage(parentStage)
    if (resizeStage != null) {
      stages << resizeStage
    }

    if (delayBeforeDisableSeconds != null && delayBeforeDisableSeconds > 0) {
      def waitStage = newStage(
        parentStage.execution, waitStage.type, "waitBeforeDisable", [waitTime: delayBeforeDisableSeconds], parentStage, SyntheticStageOwner.STAGE_AFTER
      )
      stages << waitStage
    }

    stages << disableServerGroupStage
    stages << buildApplySourceServerGroupCapacityStage(parentStage, parentStage.mapTo(ResizeStrategy.Source))
    return stages
  }

  @Nullable TargetServerGroup lookupServerGroup(Stage parentStage, String serverGroupName) {
    def fromContext = parentStage.mapTo(ResizeStrategy.Source)

    try {
      // TODO: throw some retries in there?
      return oortHelper.getTargetServerGroup(
        fromContext.credentials,
        serverGroupName,
        fromContext.location
      ).orElse(null)
    } catch(Exception e) {
      log.error('Skipping resize stage because there was an error looking up {}', serverGroupName, e)
      return null
    }
  }

  @Nullable Stage buildResizeStage(Stage parentStage) {
    def sourceServerGroup = lookupServerGroup(parentStage, rollbackServerGroupName)
    if (!sourceServerGroup) {
      return null
    }

    def targetServerGroup = lookupServerGroup(parentStage, restoreServerGroupName)
    if (!targetServerGroup) {
      return null
    }

    // using max of source and target because we don't want to scale down restoreServerGroupName if rollbackServerGroupName
    // became smaller for some reason
    ResizeStrategy.Capacity desiredCapacity = [
      max: Math.max(sourceServerGroup.capacity.max, targetServerGroup.capacity.max),
      desired: Math.max(sourceServerGroup.capacity.desired, targetServerGroup.capacity.desired)
    ]

    // let's directly produce a capacity with a pinned min instead of relying on the resize stage
    desiredCapacity.min = desiredCapacity.desired

    // for this assignment to work we rely on targetServerGroup.capacity being a map like [min: 1, desired: 2, max: 3]
    ResizeStrategy.Capacity currentCapacity = targetServerGroup.capacity
    if (currentCapacity == desiredCapacity) {
      log.info('Skipping resize stage because the current capacity of the rollback target {} is the same as the desired capacity {}',
        restoreServerGroupName, desiredCapacity)
      return null
    }

    Map resizeServerGroupContext = new HashMap(parentStage.context) + [
      action                       : ResizeStrategy.ResizeAction.scale_exact.toString(),
      capacity                     : desiredCapacity,
      asgName                      : restoreServerGroupName,
      pinMinimumCapacity           : true,
      targetHealthyDeployPercentage: targetHealthyRollbackPercentage
    ]

    return newStage(parentStage.execution, resizeServerGroupStage.type, "resize", resizeServerGroupContext, parentStage, SyntheticStageOwner.STAGE_AFTER)
  }

  Stage buildCaptureSourceServerGroupCapacityStage(Stage parentStage,
                                                   ResizeStrategy.Source source) {
    Map captureSourceServerGroupCapacityContext = [
      useSourceCapacity: true,
      source           : [
        asgName        : rollbackServerGroupName,
        serverGroupName: rollbackServerGroupName,
        region         : source.region,
        account        : source.credentials,
        cloudProvider  : source.cloudProvider
      ]
    ]
    return newStage(
      parentStage.execution,
      captureSourceServerGroupCapacityStage.type,
      "Snapshot Source Server Group",
      captureSourceServerGroupCapacityContext,
      parentStage,
      SyntheticStageOwner.STAGE_AFTER
    )
  }

  Stage buildApplySourceServerGroupCapacityStage(Stage parentStage,
                                                 ResizeStrategy.Source source) {
    Map applySourceServerGroupCapacityContext = [
      credentials  : source.credentials,
      cloudProvider: source.cloudProvider,
      target       : [
        asgName        : restoreServerGroupName,
        serverGroupName: restoreServerGroupName,
        region         : source.region,
        account        : source.credentials,
        cloudProvider  : source.cloudProvider
      ]
    ]
    return newStage(
      parentStage.execution,
      applySourceServerGroupCapacityStage.type,
      "Restore Min Capacity From Snapshot",
      applySourceServerGroupCapacityContext,
      parentStage,
      SyntheticStageOwner.STAGE_AFTER
    )
  }
}
