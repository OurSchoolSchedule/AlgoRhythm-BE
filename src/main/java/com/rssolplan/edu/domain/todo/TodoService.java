package com.rssolplan.edu.domain.todo;

import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.todo.dto.TodoCreateRequestDto;
import com.rssolplan.edu.domain.todo.dto.TodoListResponseDto;
import com.rssolplan.edu.domain.todo.dto.TodoResponseDto;
import com.rssolplan.edu.domain.todo.dto.TodoUpdateRequestDto;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoService {

    private final TodoRepository todoRepository;
    private final UserRepository userRepository;
    private final SchoolRepository schoolRepository;
    private final AuthorizationService authorizationService;

    /**
     * Returns school and handover tasks plus the user's personal tasks for a date in the
     * active school, grouped by category and newest first within each category.
     * School tasks occupy the response's storeTodos field.
     *
     * @throws com.rssolplan.edu.global.exception.NotFoundException if the user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     */
    public TodoListResponseDto getTodosByDate(Long userId, LocalDate date) {
        Long schoolId = authorizationService.getActiveSchoolIdOrThrow(userId);

        List<Todo> allTodos = todoRepository.findAllTodosForDate(schoolId, userId, date);

        List<TodoResponseDto> schoolTodos = allTodos.stream()
                .filter(t -> t.getTodoType() == Todo.TodoType.SCHOOL)
                .map(TodoResponseDto::from)
                .collect(Collectors.toList());

        List<TodoResponseDto> handoverTodos = allTodos.stream()
                .filter(t -> t.getTodoType() == Todo.TodoType.HANDOVER)
                .map(TodoResponseDto::from)
                .collect(Collectors.toList());

        List<TodoResponseDto> personalTodos = allTodos.stream()
                .filter(t -> t.getTodoType() == Todo.TodoType.PERSONAL)
                .map(TodoResponseDto::from)
                .collect(Collectors.toList());

        return TodoListResponseDto.builder()
                .date(date)
                .storeTodos(schoolTodos)
                .handoverTodos(handoverTodos)
                .personalTodos(personalTodos)
                .build();
    }

    /**
     * Creates an incomplete task in the active school and returns its details.
     * School-wide tasks require ADMIN; handover and personal tasks require school membership.
     *
     * @throws NotFoundException if the user or school does not exist
     * @throws ForbiddenException if the active school, membership, or permission is missing
     */
    @Transactional
    public TodoResponseDto createTodo(Long userId, TodoCreateRequestDto request) {
        Long schoolId = authorizationService.getActiveSchoolIdOrThrow(userId);
        SchoolUser schoolUser = authorizationService.getSchoolUserOrThrow(userId, schoolId);

        validateCreatePermission(schoolUser, request.getTodoType());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));

        Todo todo = Todo.builder()
                .school(school)
                .user(user)
                .date(request.getDate())
                .todoType(request.getTodoType())
                .content(request.getContent())
                .completed(false)
                .build();

        return TodoResponseDto.from(todoRepository.save(todo));
    }

    /**
     * Updates non-null content and completion fields for an authorized task in the active school.
     * Returns its updated details; permissions follow {@link #validateUpdateDeletePermission}.
     *
     * @throws NotFoundException if the user or task does not exist
     * @throws ForbiddenException if the active school, membership, task school, or permission is invalid
     */
    @Transactional
    public TodoResponseDto updateTodo(Long userId, Long todoId, TodoUpdateRequestDto request) {
        Long schoolId = authorizationService.getActiveSchoolIdOrThrow(userId);
        SchoolUser schoolUser = authorizationService.getSchoolUserOrThrow(userId, schoolId);

        Todo todo = todoRepository.findById(todoId)
                .orElseThrow(() -> new NotFoundException("할일을 찾을 수 없습니다."));

        if (!todo.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 할일이 아닙니다.");
        }

        validateUpdateDeletePermission(schoolUser, todo, userId);

        if (request.getContent() != null) todo.setContent(request.getContent());
        if (request.getCompleted() != null) todo.setCompleted(request.getCompleted());

        return TodoResponseDto.from(todo);
    }

    /**
     * Deletes an authorized task in the active school using {@link #validateUpdateDeletePermission}.
     *
     * @throws NotFoundException if the user or task does not exist
     * @throws ForbiddenException if the active school, membership, task school, or permission is invalid
     */
    @Transactional
    public void deleteTodo(Long userId, Long todoId) {
        Long schoolId = authorizationService.getActiveSchoolIdOrThrow(userId);
        SchoolUser schoolUser = authorizationService.getSchoolUserOrThrow(userId, schoolId);

        Todo todo = todoRepository.findById(todoId)
                .orElseThrow(() -> new NotFoundException("할일을 찾을 수 없습니다."));

        if (!todo.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 할일이 아닙니다.");
        }

        validateUpdateDeletePermission(schoolUser, todo, userId);
        todoRepository.delete(todo);
    }

    /**
     * Inverts an authorized task's completion flag and returns the updated details.
     * Uses {@link #validateUpdateDeletePermission} for a task in the active school.
     *
     * @throws NotFoundException if the user or task does not exist
     * @throws ForbiddenException if the active school, membership, task school, or permission is invalid
     */
    @Transactional
    public TodoResponseDto toggleTodoCompleted(Long userId, Long todoId) {
        Long schoolId = authorizationService.getActiveSchoolIdOrThrow(userId);
        SchoolUser schoolUser = authorizationService.getSchoolUserOrThrow(userId, schoolId);

        Todo todo = todoRepository.findById(todoId)
                .orElseThrow(() -> new NotFoundException("할일을 찾을 수 없습니다."));

        if (!todo.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 할일이 아닙니다.");
        }

        validateUpdateDeletePermission(schoolUser, todo, userId);
        todo.setCompleted(!todo.getCompleted());

        return TodoResponseDto.from(todo);
    }

    /**
     * Requires ADMIN to create a school-wide task; other task types pass this check.
     *
     * @throws ForbiddenException if a non-administrator requests a school-wide task
     */
    private void validateCreatePermission(SchoolUser schoolUser, Todo.TodoType todoType) {
        if (todoType == Todo.TodoType.SCHOOL) {
            if (schoolUser.getPosition() != SchoolUser.Position.ADMIN) {
                throw new ForbiddenException("학교 전체 할일은 ADMIN만 추가할 수 있습니다.");
            }
        }
    }

    /**
     * Requires ADMIN for school tasks, ADMIN or authorship for handover tasks, and
     * authorship for personal tasks, including when the caller is an administrator.
     *
     * @throws ForbiddenException if the caller lacks the task-specific permission
     */
    private void validateUpdateDeletePermission(SchoolUser schoolUser, Todo todo, Long userId) {
        boolean isAdmin = schoolUser.getPosition() == SchoolUser.Position.ADMIN;
        boolean isAuthor = todo.getUser().getId().equals(userId);

        switch (todo.getTodoType()) {
            case SCHOOL -> {
                if (!isAdmin) throw new ForbiddenException("학교 전체 할일은 ADMIN만 수정/삭제할 수 있습니다.");
            }
            case HANDOVER -> {
                if (!isAdmin && !isAuthor) throw new ForbiddenException("인수인계는 작성자 또는 ADMIN만 수정/삭제할 수 있습니다.");
            }
            case PERSONAL -> {
                if (!isAuthor) throw new ForbiddenException("내 할일은 본인만 수정/삭제할 수 있습니다.");
            }
        }
    }
}
