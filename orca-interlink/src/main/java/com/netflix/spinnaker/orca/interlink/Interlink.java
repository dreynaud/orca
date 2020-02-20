/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.interlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.kork.pubsub.PubsubPublishers;
import com.netflix.spinnaker.kork.pubsub.PubsubSubscribers;
import com.netflix.spinnaker.kork.pubsub.model.PubsubPublisher;
import com.netflix.spinnaker.orca.interlink.events.InterlinkEvent;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Interlink {
  private final PubsubPublisher publisher;
  private final ObjectMapper objectMapper;

  public Interlink(
      PubsubSubscribers subscribers, PubsubPublishers publishers, ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;

    // TODO: skip if not enabled? make the whole bean optional?
    publisher =
        publishers.getAll().stream()
            .filter(pubsubPublisher -> "interlink".equals(pubsubPublisher.getTopicName()))
            .findFirst()
            .orElse(null);

    if (publisher == null) {
      log.warn(
          "could not find interlink publisher in {}",
          publishers.getAll().stream()
              .map(PubsubPublisher::getTopicName)
              .collect(Collectors.joining(", ")));
    }
  }

  public void publish(InterlinkEvent event) {
    try {
      publisher.publish(objectMapper.writeValueAsString(event));
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize event {}", event, e);
    }
  }

  // TODO:
  // dynamic disable?
}
