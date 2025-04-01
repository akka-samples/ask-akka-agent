package akka.ask.indexer.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.file.Path;

public record WorkerState(String parentId, Path pathToFile, Status status) {

    public enum Status {
        INDEXING,
        IDLE,
    }

    @JsonIgnore
    public boolean isIdle() {
        return status == Status.IDLE;
    }

    public static WorkerState init(String parent, Path pathToFile) {
        return new WorkerState(parent, pathToFile, Status.IDLE);
    }

    public  WorkerState idle() {
        // note: even when idle it has a pathToFile which is the previously processed file
        // this information is required to notify the RagIndexingWorkflow that the file has been processed
        return new WorkerState(parentId, pathToFile, Status.IDLE);
    }

    public  WorkerState indexing(Path pathToFile) {
        return new WorkerState(parentId, pathToFile, Status.INDEXING);
    }
}
