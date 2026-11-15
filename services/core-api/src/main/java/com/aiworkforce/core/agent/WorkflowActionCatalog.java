package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.InternalExecutionRunController.PlatformAction;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two platform-native actions offered to a coordinator turn, with their JSON schemas. They are definitions served by the
 * Core API (the single authority); the orchestrator forwards them to the provider and never invents or extends them.
 */
final class WorkflowActionCatalog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowActionCatalog() {
    }

    static List<PlatformAction> actions() {
        return List.of(
                new PlatformAction(WorkflowAction.DELEGATE,
                        "Give ONE task to ONE worker of this workflow. The worker runs next, alone, and its result comes back to you.",
                        schema("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"participantRef\",\"task\"],"
                                + "\"properties\":{\"participantRef\":{\"type\":\"string\",\"description\":\"The worker ref, e.g. worker_1\"},"
                                + "\"task\":{\"type\":\"string\",\"maxLength\":1500,\"description\":\"What the worker must do\"},"
                                + "\"inputRanks\":{\"type\":\"array\",\"maxItems\":7,\"items\":{\"type\":\"integer\"},"
                                + "\"description\":\"Numbers of completed worker steps whose results the worker needs\"}}}")),
                new PlatformAction(WorkflowAction.FINISH,
                        "End the workflow with its final result. Only when the objective is met and no worker is running.",
                        schema("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"result\"],"
                                + "\"properties\":{\"result\":{\"type\":\"string\",\"description\":\"The final result of the workflow\"}}}")));
    }

    private static JsonNode schema(String json) {
        return JSON.readTree(json);
    }
}
