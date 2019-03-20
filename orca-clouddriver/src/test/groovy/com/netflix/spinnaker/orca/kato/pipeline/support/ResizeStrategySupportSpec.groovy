package com.netflix.spinnaker.orca.kato.pipeline.support

import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.orca.test.model.ExecutionBuilder
import spock.lang.Specification
import spock.lang.Subject

class ResizeStrategySupportSpec extends Specification {
  @Subject
  ResizeStrategySupport resizeStrategySupport

  def "test the shizz"() {
    resizeStrategySupport = new ResizeStrategySupport()
    Stage stage = ExecutionBuilder.stage {}
    stage.context = [:]
    ResizeStrategy.OptionalConfiguration config = Mock(ResizeStrategy.OptionalConfiguration)


    when:
    def outputCapacity = resizeStrategySupport.performScalingAndPinning(capacity(sourceCapacity), stage, config)

    then:
    toMap(outputCapacity) == expectedCapacity

    where:
    sourceCapacity               | unpinMin          || expectedCapacity
    [min: 1, max: 3, desired: 2] | null              || [min: 1, max: 3, desired: 2]
    [min: 1, max: 3, desired: 2] | false             || [min: 1, max: 3, desired: 2]
    [min: 1, max: 3, desired: 2] | true              || [min: 1, max: 3, desired: 2]

  }

  private Map toMap(ResizeStrategy.Capacity capacity) {
    return [min: capacity.min, max: capacity.max, desired: capacity.desired]
  }

  private capacity(Map map) {
    return new ResizeStrategy.Capacity(min: map.min, max: map.max, desired: map.desired)
  }
}
