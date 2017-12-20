/**
 * Copyright 2017 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.opentracing.contrib.spring.integration.messaging;

import static io.opentracing.contrib.spring.integration.messaging.utils.SpanAssertions.assertEvents;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;

import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * @author <a href="mailto:gytis@redhat.com">Gytis Trikleris</a>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {
    RabbitBinderTest.TestConfiguration.class,
    StreamApplication.class
})
public class RabbitBinderTest {

  @Autowired
  private Sender sender;

  @Autowired
  private Receiver receiver;

  @Autowired
  private MockTracer tracer;

  @Rule
  @Autowired
  public RabbitBrokerRule rabbitBrokerRule;

  /**
   * Sleuth flow:
   * 1. Processing message before sending it to the channel
   * 2. Parent span is null
   * 3. Name of the span will be [message:output]
   * 4. Marking span with client send
   * 5. Completed sending and current span is [Trace: 89df9af5dc34663e, Span: 89df9af5dc34663e, Parent: null,
   * exportable:false]
   * 6. Marking span with client received
   * 7. Closing messaging span [Trace: 89df9af5dc34663e, Span: 89df9af5dc34663e, Parent: null, exportable:false]
   * 8. Messaging span [Trace: 89df9af5dc34663e, Span: 89df9af5dc34663e, Parent: null, exportable:false] successfully
   * closed
   * 9. Processing message before sending it to the channel
   * 10. Parent span is [Trace: 89df9af5dc34663e, Span: 89df9af5dc34663e, Parent: null, exportable:false]
   * 11. Name of the span will be [message:input]
   * 12. Marking span with server received
   * 13. Completed sending and current span is [Trace: 89df9af5dc34663e, Span: f11085cf0d323b28, Parent:
   * 89df9af5dc34663e, exportable:false]
   * 14. Marking span with server send
   * 15. Closing messaging span [Trace: 89df9af5dc34663e, Span: f11085cf0d323b28, Parent: 89df9af5dc34663e,
   * exportable:false]
   * 16. Messaging span [Trace: 89df9af5dc34663e, Span: f11085cf0d323b28, Parent: 89df9af5dc34663e, exportable:false]
   * successfully closed
   */
  @Test
  public void testFlowFromSourceToSink() {
    sender.send("Ping");

    await().atMost(5, SECONDS)
        .until(receiver::getReceivedMessages, hasSize(1));

    List<MockSpan> finishedSpans = tracer.finishedSpans();
    assertThat(finishedSpans).hasSize(2);

    MockSpan outputSpan = getSpanByOperation("send:output");
    assertThat(outputSpan.parentId()).isEqualTo(0);
    assertEvents(outputSpan, Arrays.asList(Events.CLIENT_SEND, Events.CLIENT_RECEIVE));
    assertThat(outputSpan.tags()).hasSize(3);
    assertThat(outputSpan.tags()).containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_PRODUCER);
    assertThat(outputSpan.tags()).containsEntry(Tags.COMPONENT.getKey(), OpenTracingChannelInterceptor.COMPONENT_NAME);
    assertThat(outputSpan.tags()).containsEntry(Tags.MESSAGE_BUS_DESTINATION.getKey(), "output");

    MockSpan inputSpan = getSpanByOperation("receive:input");
    assertThat(inputSpan.parentId()).isEqualTo(outputSpan.context().spanId());
    assertEvents(inputSpan, Arrays.asList(Events.SERVER_RECEIVE, Events.SERVER_SEND));
    assertThat(inputSpan.tags()).hasSize(3);
    assertThat(inputSpan.tags()).containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CONSUMER);
    assertThat(inputSpan.tags()).containsEntry(Tags.COMPONENT.getKey(), OpenTracingChannelInterceptor.COMPONENT_NAME);
    assertThat(inputSpan.tags()).containsEntry(Tags.MESSAGE_BUS_DESTINATION.getKey(), "input");

    assertThat(outputSpan.startMicros()).isLessThanOrEqualTo(inputSpan.startMicros());
  }

  private MockSpan getSpanByOperation(String operationName) {
    return tracer.finishedSpans()
        .stream()
        .filter(s -> operationName.equals(s.operationName()))
        .findAny()
        .orElseThrow(
            () -> new RuntimeException(String.format("Span for operation '%s' doesn't exist", operationName)));
  }

  @Configuration
  public static class TestConfiguration {
    @Bean
    public RabbitBrokerRule rabbitBrokerRule(RabbitTemplate rabbitTemplate) {
      return new RabbitBrokerRule(rabbitTemplate);
    }
  }
}