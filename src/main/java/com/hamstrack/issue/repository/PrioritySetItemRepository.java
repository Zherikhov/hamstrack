package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.PrioritySetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PrioritySetItemRepository extends JpaRepository<PrioritySetItem, UUID> {

    List<PrioritySetItem> findAllBySetOrderByPosition(PrioritySet set);

    long countByPriority(Priority priority);

    void deleteAllBySet(PrioritySet set);

    void deleteAllByPriority(Priority priority);

    @Query("select distinct i.set from PrioritySetItem i where i.priority.id = :priorityId")
    List<PrioritySet> findSetsUsingPriority(@Param("priorityId") UUID priorityId);
}
