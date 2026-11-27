package com.rssolplan.edu.domain.demoreset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rssolplan.edu.domain.auth.passkey.PasskeyService;
import com.rssolplan.edu.domain.auth.passkey.PasskeyStore;
import com.rssolplan.edu.domain.schedule.candidates.School42Round;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class DemoSetupTest {
  @Test void setupRequiresDesignatedVerifiedOperatorEvenWhenSchoolAdmin() {
    var db = mock(JdbcTemplate.class);
    var identities = mock(PasskeyStore.class);
    var round = mock(School42Round.class);
    var service = service(db, identities, round);
    when(db.queryForObject("select database()", String.class)).thenReturn("test");
    when(identities.verifiedUserId("operator@example.invalid")).thenReturn(1L);
    assertThatThrownBy(() -> service.prepare(2L)).isInstanceOf(SecurityException.class);
    verifyNoInteractions(round);
  }

  @Test void readOnlyStatusDoesNotCreateFixtureAndPreparationDoesNotResetIt() {
    var db = mock(JdbcTemplate.class);
    var identities = mock(PasskeyStore.class);
    var round = mock(School42Round.class);
    var service = service(db, identities, round);
    when(db.queryForObject("select database()", String.class)).thenReturn("test");
    when(identities.verifiedUserId("operator@example.invalid")).thenReturn(1L);
    when(db.queryForObject(anyString(), eq(Boolean.class), eq(1L))).thenReturn(true);
    when(round.setupStatus()).thenReturn(new School42Round.Setup(null, null));
    assertThat(service.setupStatus(1L).scope()).isNull();
    verify(round, never()).prepareTable();
    verify(round, never()).prepare();
    var ready = new School42Round.Setup(new School42Round.TimetableScope(540001,547001,1,0,0),"TESTCODE");
    when(round.prepare()).thenReturn(ready);
    assertThat(service.prepare(1L)).isEqualTo(ready);
    verify(round).prepareTable();
    verify(round).prepare();
    verify(round, never()).resetTimetables(any());
  }

  private DemoResetService service(JdbcTemplate db, PasskeyStore identities, School42Round round) {
    var service = new DemoResetService(db, mock(StringRedisTemplate.class), identities, mock(PasskeyService.class), round);
    ReflectionTestUtils.setField(service,"operator","operator@example.invalid");
    ReflectionTestUtils.setField(service,"database","test");
    ReflectionTestUtils.setField(service,"pinHash","configured");
    return service;
  }
}
