package com.rssolplan.edu.domain.todo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findBySchool_IdAndDateAndTodoType(Long schoolId, LocalDate date, Todo.TodoType todoType);

    List<Todo> findBySchool_IdAndDateAndTodoTypeOrderByCreatedAtDesc(Long schoolId, LocalDate date, Todo.TodoType todoType);

    List<Todo> findByUser_IdAndSchool_IdAndDateAndTodoType(Long userId, Long schoolId, LocalDate date, Todo.TodoType todoType);

    @Query("SELECT t FROM Todo t WHERE t.school.id = :schoolId AND t.date = :date " +
            "AND (t.todoType IN ('SCHOOL', 'HANDOVER') OR (t.todoType = 'PERSONAL' AND t.user.id = :userId)) " +
            "ORDER BY t.todoType, t.createdAt DESC")
    List<Todo> findAllTodosForDate(@Param("schoolId") Long schoolId,
                                   @Param("userId") Long userId,
                                   @Param("date") LocalDate date);
}
