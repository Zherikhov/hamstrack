package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.IssueTypeSetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface IssueTypeSetItemRepository extends JpaRepository<IssueTypeSetItem, UUID> {

    List<IssueTypeSetItem> findAllBySetOrderByPosition(IssueTypeSet set);

    long countByType(IssueType type);

    void deleteAllBySet(IssueTypeSet set);

    boolean existsBySetAndType(IssueTypeSet set, IssueType type);

    @Query("select distinct i.set from IssueTypeSetItem i where i.type.id = :typeId")
    List<IssueTypeSet> findSetsUsingType(@Param("typeId") UUID typeId);

    // Types of a set, by id, in display order — feeds the cached effective config
    // (selecting the IssueType directly keeps it fully loaded for detached reads)
    @Query("select i.type from IssueTypeSetItem i where i.set.id = :setId order by i.position")
    List<IssueType> findTypesBySetId(@Param("setId") UUID setId);
}
