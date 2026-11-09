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
    final com.termux.app.compat.profile.CompatibilityProfile compatibility;
    final ObservationSource networkObservations;
    /** Optional resource, service, backup and port services; each hides its own methods when absent. */
    final Extras extras;
    static final class Extras {
        final com.termux.app.control.resource.ResourceService resource;
        final com.termux.app.control.workspace.WorkspaceServiceController services;
        final com.termux.app.control.workspace.WorkspaceBackupService backup;
        final com.termux.app.control.workspace.WorkspacePortService ports;
        Extras(com.termux.app.control.resource.ResourceService resource, com.termux.app.control.workspace.WorkspaceServiceController services,
               com.termux.app.control.workspace.WorkspaceBackupService backup, com.termux.app.control.workspace.WorkspacePortService ports) {
            this.resource = resource; this.services = services; this.backup = backup; this.ports = ports;
        }
        static final Extras NONE = new Extras(null, null, null, null);
    }
    interface ObservationSource { java.util.Map<String, Object> current(); }

    DomainServices(JobCoordinator jobs, JobAuthority jobAuthority, ProcessService processes, FileService files,
                   WorkspaceRepository workspaces) {
        this(jobs, jobAuthority, processes, files, workspaces, null, null);
    }

    DomainServices(JobCoordinator jobs, JobAuthority jobAuthority, ProcessService processes, FileService files,
                   WorkspaceRepository workspaces, com.termux.app.compat.profile.CompatibilityProfile compatibility) {
        this(jobs, jobAuthority, processes, files, workspaces, compatibility, null);
    }

    DomainServices(JobCoordinator jobs, JobAuthority jobAuthority, ProcessService processes, FileService files,
                   WorkspaceRepository workspaces, com.termux.app.compat.profile.CompatibilityProfile compatibility,
                   ObservationSource networkObservations) {
        if ((jobs == null) != (jobAuthority == null)) throw new IllegalArgumentException("job coordinator and authority required together");
        this.jobs = jobs; this.jobAuthority = jobAuthority; this.processes = processes; this.files = files; this.workspaces = workspaces;
        this.compatibility = compatibility;
        this.networkObservations = networkObservations;
        this.extras = Extras.NONE;
    }

    private DomainServices(DomainServices base, Extras extras) {
        jobs = base.jobs; jobAuthority = base.jobAuthority; processes = base.processes; files = base.files; workspaces = base.workspaces;
        compatibility = base.compatibility; networkObservations = base.networkObservations; this.extras = extras == null ? Extras.NONE : extras;
    }

    /** The same services plus the optional ones. Each needs the jobs or workspaces it builds on, else it is dropped. */
    DomainServices withExtras(Extras extras) {
        Extras safe = extras == null ? Extras.NONE : new Extras(
            jobs != null ? extras.resource : null,
            jobs != null && workspaces != null ? extras.services : null,
            workspaces != null ? extras.backup : null,
            workspaces != null ? extras.ports : null);
        return new DomainServices(this, safe);
    }

    /** Feature names that gate method families: "jobs", "processes", "files" and "workspaces". */
    Set<String> features() {
        Set<String> features = new HashSet<>();
        if (jobs != null) features.add("jobs");
        if (processes != null) features.add("processes");
        if (files != null) features.add("files");
        if (workspaces != null) features.add("workspaces");
        if (extras.resource != null) features.add("resource");
        if (extras.services != null) features.add("workspace-services");
        if (extras.backup != null) features.add("workspace-backup");
        if (extras.ports != null) features.add("workspace-ports");
        return features;
    }
}
