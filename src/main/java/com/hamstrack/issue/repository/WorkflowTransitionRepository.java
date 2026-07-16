package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.issue.entity.WorkflowTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {

    List<WorkflowTransition> findAllByWorkflow(Workflow workflow);

    List<WorkflowTransition> findAllByWorkflowAndFromStatus(Workflow workflow, Status fromStatus);

    void deleteAllByWorkflow(Workflow workflow);

    void deleteAllByFromStatusOrToStatus(Status fromStatus, Status toStatus);
}
