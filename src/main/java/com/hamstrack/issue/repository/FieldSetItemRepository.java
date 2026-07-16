package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FieldSetItemRepository extends JpaRepository<FieldSetItem, UUID> {

    List<FieldSetItem> findAllBySetOrderByPosition(FieldSet set);

    long countByField(FieldDef field);

    void deleteAllBySet(FieldSet set);

    @Query("select distinct i.set from FieldSetItem i where i.field.id = :fieldId")
    List<FieldSet> findSetsUsingField(@Param("fieldId") UUID fieldId);
}
