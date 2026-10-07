package com.rssolplan.edu.domain.schedule.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.domain.notification.NotificationService;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableRequest;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.global.security.AuthorizationService;
import org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.pojo.bytebuddy.ByteBuddyProxyFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleGenerationRequestTest {

    @Mock private SchoolRepository schoolRepository;
    @Mock private TimetableRequestRepository timetableRequestRepository;
    @Mock private AuthorizationService authService;
    @Mock private NotificationService notificationService;
    @InjectMocks private ScheduleGenerationService service;

    @Test
    void requestResponseSerializesWithAnUninitializedSchoolProxy() throws Exception {
        Long userId = 100L;
        Long schoolId = 200L;
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 9, 0);

        // A real detached Hibernate proxy reproduces the old entity serialization failure.
        ByteBuddyProxyFactory factory = new ByteBuddyProxyFactory(
                new BytecodeProviderImpl().getByteBuddyProxyHelper());
        factory.postInstantiate("School", School.class, Set.of(HibernateProxy.class),
                School.class.getMethod("getId"), School.class.getMethod("setId", Long.class), null);
        School school = (School) factory.getProxy(schoolId, null);
        SchoolUser admin = SchoolUser.builder().position(SchoolUser.Position.ADMIN).build();
        when(authService.getActiveSchoolIdOrThrow(userId)).thenReturn(schoolId);
        when(authService.getSchoolUserOrThrow(userId, schoolId)).thenReturn(admin);
        when(schoolRepository.findById(schoolId)).thenReturn(Optional.of(school));
        when(timetableRequestRepository.save(any(TimetableRequest.class))).thenAnswer(invocation -> {
            TimetableRequest request = invocation.getArgument(0);
            request.setId(1203L);
            request.setCreatedAt(now);
            request.setUpdatedAt(now);
            return request;
        });

        var response = new ScheduleGenerationController(service).requestTimetable(userId);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response.getBody()));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(1203L, json.get("id").asLong());
        assertEquals("REQUESTED", json.get("status").asText());
        assertEquals(schoolId.longValue(), json.get("schoolId").asLong());
        assertNotNull(json.get("createdAt"));
        assertNotNull(json.get("updatedAt"));
        assertEquals(5, json.size());
        assertTrue(((HibernateProxy) school).getHibernateLazyInitializer().isUninitialized());
        verify(notificationService).sendTimetableInputRequest(userId, schoolId);
        verify(timetableRequestRepository).save(any(TimetableRequest.class));
    }
}
