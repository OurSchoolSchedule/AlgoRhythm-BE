package com.rssolplan.edu.domain.school.setting;

import com.rssolplan.edu.domain.school.School;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "school_setting")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SchoolSetting {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id", nullable = false, unique = true)
    private School school;

    /** 한 교시당 수업 시간(분) */
    @Column(nullable = false)
    private int periodDuration;

    /** 쉬는 시간(분) */
    @Column(nullable = false)
    private int breakDuration;

    /** 점심시간 시작 */
    private LocalTime lunchStartTime;

    /** 점심시간 종료 */
    private LocalTime lunchEndTime;

    @OneToMany(mappedBy = "schoolSetting", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PeriodSetting> periods = new ArrayList<>();

    @CreationTimestamp
    private Timestamp createdAt;

    @UpdateTimestamp
    private Timestamp updatedAt;

    /** Adds the period to this setting's collection and sets its owning setting to this instance. */
    public void addPeriod(PeriodSetting period) {
        periods.add(period);
        period.setSchoolSetting(this);
    }

    /** Clears the period collection; persisted children are removed through JPA orphan removal. */
    public void clearPeriods() {
        periods.clear();
    }
}
