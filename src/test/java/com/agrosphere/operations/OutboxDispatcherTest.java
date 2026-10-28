package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agrosphere.operations.infrastructure.messaging.OutboxDispatcher;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.*;

class OutboxDispatcherTest {
  JdbcTemplate jdbc = mock(JdbcTemplate.class);
  RabbitTemplate rabbit = mock(RabbitTemplate.class);
  UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @SuppressWarnings("unchecked")
  void pending(int attempts) {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(20)))
        .thenReturn(
            List.of(
                new OutboxDispatcher.Pending(
                    id,
                    "EstateCreated",
                    2,
                    "{\"correlationId\":\"00000000-0000-0000-0000-000000000001\"}",
                    attempts)));
  }

  OutboxDispatcher dispatcher() {
    return new OutboxDispatcher(jdbc, rabbit, 3, 20, 5, 1);
  }

  @Test
  void exponentialRetryIsCapped() {
    assertEquals(5, OutboxDispatcher.retryDelaySeconds(1, 5));
    assertEquals(10, OutboxDispatcher.retryDelaySeconds(2, 5));
    assertEquals(3600, OutboxDispatcher.retryDelaySeconds(40, 5));
  }

  @Test
  void confirmedMessagesAreMarkedPublished() {
    pending(0);
    doAnswer(
            i -> {
              CorrelationData c = i.getArgument(3);
              c.getFuture().complete(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    dispatcher().dispatch();
    verify(jdbc).update(contains("delivery_status='PUBLISHED'"), eq(id));
  }

  @Test
  void failedPublicationSchedulesRetry() {
    pending(0);
    doThrow(new IllegalStateException())
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    dispatcher().dispatch();
    verify(jdbc).update(contains("next_attempt_at"), eq(1), eq("PENDING"), any(), eq(id));
  }

  @Test
  void retryBudgetExhaustionRetainsFailedRecord() {
    pending(2);
    doThrow(new IllegalStateException())
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    dispatcher().dispatch();
    verify(jdbc).update(contains("next_attempt_at"), eq(3), eq("FAILED"), any(), eq(id));
  }

  @Test
  void duplicateDeliveryKeepsSameEventIdentity() {
    pending(0);
    var sent = new ArrayList<String>();
    doAnswer(
            i -> {
              CorrelationData c = i.getArgument(3);
              sent.add(c.getId());
              c.getFuture().complete(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    dispatcher().dispatch();
    dispatcher().dispatch();
    assertEquals(List.of(id.toString(), id.toString()), sent);
  }

  @Test
  void unroutableMessageIsRetriedEvenAfterAck() {
    pending(0);
    doAnswer(
            i -> {
              CorrelationData c = i.getArgument(3);
              c.setReturned(
                  new ReturnedMessage(
                      new Message(new byte[0], new MessageProperties()),
                      312,
                      "NO_ROUTE",
                      "agrosphere.domain",
                      "agrosphere.EstateCreated.v2"));
              c.getFuture().complete(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    dispatcher().dispatch();
    verify(jdbc).update(contains("next_attempt_at"), eq(1), eq("PENDING"), any(), eq(id));
  }

  @Test
  void negativeConfirmationIsRetried() {
    pending(0);
    doAnswer(
            i -> {
              CorrelationData c = i.getArgument(3);
              c.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
              return null;
            })
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    dispatcher().dispatch();
    verify(jdbc).update(contains("next_attempt_at"), eq(1), eq("PENDING"), any(), eq(id));
  }

  @Test void missingConfirmationTimesOutWithoutPublishedMark() {
    pending(0);
    dispatcher().dispatch();
    verify(jdbc).update(contains("next_attempt_at"), eq(1), eq("PENDING"), any(), eq(id));
    verify(jdbc,never()).update(contains("delivery_status='PUBLISHED'"),eq(id));
  }
}
