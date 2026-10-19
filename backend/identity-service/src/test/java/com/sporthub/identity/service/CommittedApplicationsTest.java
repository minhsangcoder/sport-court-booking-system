package com.sporthub.identity.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
class CommittedApplicationsTest {
 @Test void approvalGateUsesOneQueryAndPreservesOrderDistinctnessAndLimits() {
  var jdbc=mock(JdbcTemplate.class);var service=new OwnerApplicationService(jdbc,new ObjectMapper(),null,null,mock(PlatformTransactionManager.class),null,null,null,mock(UserSecurityLock.class));
  UUID first=UUID.randomUUID(),second=UUID.randomUUID(),draft=UUID.randomUUID();
  when(jdbc.queryForList(anyString(),eq(UUID.class),any(Object[].class))).thenReturn(List.of(second,first));
  assertThat(service.committed(List.of(first,draft,second,first))).containsExactly(first,second);
  verify(jdbc,times(1)).queryForList(anyString(),eq(UUID.class),any(Object[].class));
  assertThat(service.committed(List.of())).isEmpty();
  assertThatThrownBy(() -> service.committed(Collections.nCopies(201,first))).isInstanceOf(com.sporthub.identity.exception.IdentityException.class);
 }
}
