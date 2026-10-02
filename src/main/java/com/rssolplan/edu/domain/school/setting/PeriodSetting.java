package com.rssolplan.edu.domain.school.setting;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

@Entity
@Table(name = "period_setting",
        uniqueConstraints = @UniqueConstraint(columnNames = {"school_setting_id", "period_number"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PeriodSetting {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_setting_id", nullable = false)
    private SchoolSetting schoolSetting;

    /** 교시 번호 (1교시, 2교시 ...) */
    @Column(name = "period_number", nullable = false)
    private int periodNumber;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;
}
