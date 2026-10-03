package com.rssolplan.edu.domain.school;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "school")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class School {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String schoolCode;

    private String name;
    private String address;
    private String phoneNumber;

    @Column(columnDefinition = "json")
    private String settings;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void upd() { updatedAt = LocalDateTime.now(); }
}
