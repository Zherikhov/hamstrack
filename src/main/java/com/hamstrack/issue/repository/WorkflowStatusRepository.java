package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.issue.entity.WorkflowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WorkflowStatusRepository extends JpaRepository<WorkflowStatus, UUID> {

    List<WorkflowStatus> findAllByWorkflowOrderByPosition(Workflow workflow);

    boolean existsByWorkflowAndStatus(Workflow workflow, Status status);

    long countByStatus(Status status);

    void deleteAllByWorkflow(Workflow workflow);

    void deleteAllByStatus(Status status);

    @Query("select distinct ws.workflow from WorkflowStatus ws where ws.status.id = :statusId")
    List<Workflow> findWorkflowsUsingStatus(@Param("statusId") UUID statusId);
}
