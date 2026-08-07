package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.issue.entity.WorkflowTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {

    List<WorkflowTransition> findAllByWorkflow(Workflow workflow);

    List<WorkflowTransition> findAllByWorkflowAndFromStatus(Workflow workflow, Status fromStatus);

    void deleteAllByWorkflow(Workflow workflow);

    void deleteAllByFromStatusOrToStatus(Status fromStatus, Status toStatus);

    // Transitions of a workflow, by id, with from/to statuses fetched — feeds the
    // cached effective config so its detached entities are safe to read
    @Query("select t from WorkflowTransition t left join fetch t.fromStatus join fetch t.toStatus "
            + "where t.workflow.id = :workflowId")
    List<WorkflowTransition> findByWorkflowIdWithStatuses(@Param("workflowId") UUID workflowId);
}
