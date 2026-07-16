package com.hamstrack.admin.service;

import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * "Used by N projects" counters. Projects with a NULL binding implicitly use
 * the system default, so defaults also count the unbound projects.
 */
@Service
@RequiredArgsConstructor
public class ProjectCountService {

    private final ProjectRepository projectRepository;

    public long projectsUsingWorkflow(Workflow workflow) {
        long bound = projectRepository.countByWorkflowId(workflow.getId());
        return workflow.isSystemDefault() ? bound + projectRepository.countByWorkflowIdIsNull() : bound;
    }

    public long projectsUsingPrioritySet(PrioritySet set) {
        long bound = projectRepository.countByPrioritySetId(set.getId());
        return set.isSystemDefault() ? bound + projectRepository.countByPrioritySetIdIsNull() : bound;
    }

    public long projectsUsingIssueTypeSet(IssueTypeSet set) {
        long bound = projectRepository.countByIssueTypeSetId(set.getId());
        return set.isSystemDefault() ? bound + projectRepository.countByIssueTypeSetIdIsNull() : bound;
    }

    // ---- listings for the "where is this used?" popovers ----

    public List<Project> projectsListUsingWorkflow(Workflow workflow) {
        var list = new ArrayList<>(projectRepository.findAllByWorkflowId(workflow.getId()));
        if (workflow.isSystemDefault()) list.addAll(projectRepository.findAllByWorkflowIdIsNull());
        return list;
    }

    public List<Project> projectsListUsingPrioritySet(PrioritySet set) {
        var list = new ArrayList<>(projectRepository.findAllByPrioritySetId(set.getId()));
        if (set.isSystemDefault()) list.addAll(projectRepository.findAllByPrioritySetIdIsNull());
        return list;
    }

    public List<Project> projectsListUsingFieldSet(FieldSet set) {
        var list = new ArrayList<>(projectRepository.findAllByFieldSetId(set.getId()));
        if (set.isSystemDefault()) list.addAll(projectRepository.findAllByFieldSetIdIsNull());
        return list;
    }

    public List<Project> projectsListUsingIssueTypeSet(IssueTypeSet set) {
        var list = new ArrayList<>(projectRepository.findAllByIssueTypeSetId(set.getId()));
        if (set.isSystemDefault()) list.addAll(projectRepository.findAllByIssueTypeSetIdIsNull());
        return list;
    }
}
