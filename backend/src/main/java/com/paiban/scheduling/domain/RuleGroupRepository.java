package com.paiban.scheduling.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleGroupRepository extends JpaRepository<RuleGroup, Long> {

    List<RuleGroup> findByDeletedAtIsNullOrderByGroupTypeAscNameAsc();

    List<RuleGroup> findByGroupTypeAndDeletedAtIsNullOrderByNameAsc(RuleGroupType groupType);

    List<RuleGroup> findBySemesterIdAndDeletedAtIsNullOrderByNameAsc(Long semesterId);

    Optional<RuleGroup> findByLiveKey(String liveKey);

    Optional<RuleGroup> findByIdAndDeletedAtIsNull(Long id);

    List<RuleGroup> findByIdIn(Collection<Long> ids);
}
