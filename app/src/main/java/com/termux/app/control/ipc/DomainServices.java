package com.termux.app.control.ipc;

import com.termux.app.control.files.FileService;
import com.termux.app.control.jobs.JobAuthority;
import com.termux.app.control.jobs.JobCoordinator;
import com.termux.app.control.process.ProcessService;
import com.termux.app.control.workspace.WorkspaceRepository;
import java.util.HashSet;
import java.util.Set;

/**
 * Trusted domain services for one dispatcher. Each service is optional; a missing service hides its methods
 * from discovery and returns method-not-found. Services are supplied by server wiring, never by a client.
 */
final class DomainServices {
    static final DomainServices NONE = new DomainServices(null, null, null, null, null);
    final JobCoordinator jobs;
    final JobAuthority jobAuthority;
    final ProcessService processes;
    final FileService files;
    final WorkspaceRepository workspaces;

    DomainServices(JobCoordinator jobs, JobAuthority jobAuthority, ProcessService processes, FileService files,
                   WorkspaceRepository workspaces) {
        if ((jobs == null) != (jobAuthority == null)) throw new IllegalArgumentException("job coordinator and authority required together");
        this.jobs = jobs; this.jobAuthority = jobAuthority; this.processes = processes; this.files = files; this.workspaces = workspaces;
    }

    /** Feature names that gate method families: "jobs", "processes", "files" and "workspaces". */
    Set<String> features() {
        Set<String> features = new HashSet<>();
        if (jobs != null) features.add("jobs");
        if (processes != null) features.add("processes");
        if (files != null) features.add("files");
        if (workspaces != null) features.add("workspaces");
        return features;
    }
}
