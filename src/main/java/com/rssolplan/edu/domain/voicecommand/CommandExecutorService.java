package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.substitute.SubstituteService;
import com.rssolplan.edu.domain.schedule.substitute.dto.SubstituteApprovalRequest;
import com.rssolplan.edu.domain.schedule.substitute.dto.SubstituteCreateRequest;
import com.rssolplan.edu.domain.schedule.substitute.dto.SubstituteRespondRequest;
import com.rssolplan.edu.domain.schedule.workavailability.TeacherAvailabilityService;
import com.rssolplan.edu.domain.schedule.workavailability.dto.TeacherAvailabilityRequestDto;
import com.rssolplan.edu.domain.voicecommand.dto.ParsedIntent;
import com.rssolplan.edu.global.exception.IntentParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CommandExecutorService {

    private final SubstituteService substituteService;
    private final TeacherAvailabilityService availabilityService;

    public Object execute(Long userId, ParsedIntent intent) {
        return switch (intent.resolvedType()) {
            case SUBSTITUTE_CREATE -> executeSubstituteCreate(userId, intent);
            case SUBSTITUTE_RESPOND -> executeSubstituteRespond(userId, intent);
            case SUBSTITUTE_APPROVE -> executeSubstituteApprove(userId, intent);
            case AVAILABILITY_ADD -> executeAvailabilityAdd(userId, intent);
            case AVAILABILITY_REPLACE -> executeAvailabilityReplace(userId, intent);
            case UNKNOWN -> throw new IntentParseException("실행 가능한 명령이 아닙니다.");
        };
    }

    private Object executeSubstituteCreate(Long userId, ParsedIntent intent) {
        if (intent.timetableId() == null || intent.substituteDate() == null) {
            throw new IntentParseException("보결 요청에 필요한 정보가 부족합니다. (시간표 ID, 날짜 필요)");
        }
        SubstituteCreateRequest req = new SubstituteCreateRequest(
                intent.timetableId(),
                LocalDate.parse(intent.substituteDate()),
                intent.note()
        );
        return substituteService.create(userId, req);
    }

    private Object executeSubstituteRespond(Long userId, ParsedIntent intent) {
        if (intent.requestId() == null || intent.action() == null) {
            throw new IntentParseException("보결 응답에 필요한 정보가 부족합니다. (요청 ID, action 필요)");
        }
        return substituteService.respond(userId, intent.requestId(),
                new SubstituteRespondRequest(intent.action()));
    }

    private Object executeSubstituteApprove(Long userId, ParsedIntent intent) {
        if (intent.responseId() == null || intent.action() == null) {
            throw new IntentParseException("보결 승인에 필요한 정보가 부족합니다. (응답 ID, action 필요)");
        }
        return substituteService.approve(userId, intent.responseId(),
                new SubstituteApprovalRequest(intent.action()));
    }

    private Object executeAvailabilityAdd(Long userId, ParsedIntent intent) {
        return availabilityService.addUnavailabilities(userId, toAvailabilityDto(intent));
    }

    private Object executeAvailabilityReplace(Long userId, ParsedIntent intent) {
        return availabilityService.replaceUnavailabilities(userId, toAvailabilityDto(intent));
    }

    /**
     * ParsedIntent의 unavailabilitySlots를 TeacherAvailabilityRequestDto로 변환한다.
     * TeacherAvailabilityRequestDto에 setter가 없으므로 리플렉션으로 필드를 주입한다.
     */
    private TeacherAvailabilityRequestDto toAvailabilityDto(ParsedIntent intent) {
        if (intent.unavailabilitySlots() == null || intent.unavailabilitySlots().isEmpty()) {
            throw new IntentParseException("불가 교시 정보가 없습니다.");
        }
        try {
            List<TeacherAvailabilityRequestDto.AvailabilityItem> items =
                    intent.unavailabilitySlots().stream().map(slot -> {
                        try {
                            TeacherAvailabilityRequestDto.AvailabilityItem item =
                                    new TeacherAvailabilityRequestDto.AvailabilityItem();
                            setField(item, "dayOfWeek", DayOfWeek.valueOf(slot.dayOfWeek()));
                            setField(item, "periodNumber", slot.periodNumber());
                            setField(item, "reason", slot.reason());
                            return item;
                        } catch (Exception e) {
                            throw new IntentParseException("불가 교시 변환 중 오류: " + e.getMessage());
                        }
                    }).toList();

            TeacherAvailabilityRequestDto dto = new TeacherAvailabilityRequestDto();
            setField(dto, "unavailabilities", items);
            return dto;
        } catch (IntentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new IntentParseException("불가 교시 DTO 변환 중 오류: " + e.getMessage());
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), name);
            throw e;
        }
    }
}
