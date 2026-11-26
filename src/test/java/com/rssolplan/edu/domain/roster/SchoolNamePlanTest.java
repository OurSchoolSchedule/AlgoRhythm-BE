package com.rssolplan.edu.domain.roster;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class SchoolNamePlanTest {
    private static SchoolNamePlan plan(Long own, List<SchoolNamePlan.Row> rows) {
        return SchoolNamePlan.build(42, "학교", 1, "김민지", own, rows, Set.of());
    }

    @Test
    void stalePreviewRequiresAcknowledgmentOfBothNewNamesAndReplayKeepsMembershipName() {
        var first = plan(null, List.of()).proposal;
        var changed = plan(null, List.of(new SchoolNamePlan.Row(10, "김민지", "김민지")));
        assertThat(changed.proposal.renamedTo()).isEqualTo("김민지A");
        assertThat(changed.proposal.displayName()).isEqualTo("김민지B");
        assertThatThrownBy(() -> changed.requireConfirmation(first.confirmation())).isInstanceOf(SchoolNamePlan.Changed.class);
        changed.requireConfirmation(changed.proposal.confirmation());
        var group = List.of(new SchoolNamePlan.Row(10, "김민지A", "김민지"), new SchoolNamePlan.Row(11, "김민지B", "김민지"));
        assertThat(plan(null, group).proposal.displayName()).isEqualTo("김민지C");
        var replay = plan(11L, group);
        replay.requireConfirmation(changed.proposal.confirmation());
        assertThat(replay.proposal.displayName()).isEqualTo("김민지B");
        assertThat(replay.replay).isTrue();
    }

    @Test
    void reservesLegacyNamesUntilOwnerConfirmsAndSkipsUsedSuffixesBeyondZ() {
        var old = List.of(new SchoolNamePlan.Row(10, "김민지", null), new SchoolNamePlan.Row(11, "김민지", null));
        var later = plan(11L, old).proposal;
        assertThat(later.displayName()).isEqualTo("김민지B");
        assertThat(later.renamedTo()).isNull();
        var rows = new ArrayList<>(old);
        for (char suffix = 'A'; suffix <= 'Z'; suffix++) {
            rows.add(new SchoolNamePlan.Row(100 + suffix, "김민지" + suffix, null));
        }
        assertThat(plan(null, rows).proposal.displayName()).isEqualTo("김민지AA");
        var other = SchoolNamePlan.build(7, "다른 학교", 1, "김민지", null, List.of(), Set.of());
        assertThat(other.proposal.displayName()).isEqualTo("김민지");
    }
}
