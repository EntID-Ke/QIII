package io.github.qqmodulemanager.core;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Startup selection is independent of Android, module load order and UI state. */
public final class ProviderSelector {
    public interface Candidate {
        boolean ready();
        boolean failed();
        boolean initialized();
        boolean initialize() throws Exception;
        void recordFailure(Throwable failure);
        boolean dirtyOnFailure();
    }
    public static final class Result {
        public final String owner, phase;
        public Result(String owner,String phase) { this.owner=owner;this.phase=phase; }
    }
    public static Result select(List<String> order,Set<String> participants,
                                Function<String,Candidate> lookup) {
        for(String module:order) {
            if(!participants.contains(module))continue;
            Candidate candidate=lookup.apply(module);
            if(candidate==null || !candidate.ready())return new Result("","waiting");
            if(candidate.failed())continue;
            if(!candidate.initialized()) {
                try {
                    if(!candidate.initialize())throw new IllegalStateException("InitializationFailed");
                } catch(Exception | LinkageError | AssertionError failure) {
                    candidate.recordFailure(failure);
                    if(candidate.dirtyOnFailure())return new Result("","restart_required");
                    continue;
                }
            }
            return new Result(module,"active");
        }
        return new Result("","unavailable");
    }
}
