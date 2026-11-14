package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.ToolViews.ApprovalView;
import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
import com.aiworkforce.core.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Human review of tool actions requested by AI agents. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}/approvals", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Tool approvals", description = "A person approves or rejects ONE exact tool action of an AI agent")
public class ApprovalController {

    @Schema(name = "ApproveToolActionRequest",
            description = "The hash of the arguments the reviewer saw. It must equal the stored one: a stale or altered request is refused.")
    public record ApproveRequest(@NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String argumentsHash) {
    }

    @Schema(name = "RejectToolActionRequest")
    public record RejectRequest(@Size(max = 500) String reason) {
    }

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    @Operation(operationId = "listToolApprovals", summary = "Approval requests, newest first (filter by status or execution)")
    public PageResponse<ApprovalView> list(@PathVariable UUID organizationId, AuthenticatedUser user,
            @RequestParam(required = false) ApprovalStatus status, @RequestParam(required = false) UUID executionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(approvals.list(organizationId, user, status, executionId,
                PageParams.of(page, size, Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id")))), Function.identity());
    }

    @GetMapping("/{approvalId}")
    @Operation(operationId = "getToolApproval", summary = "One approval request with the exact action it concerns")
    public ApprovalView get(@PathVariable UUID organizationId, @PathVariable UUID approvalId, AuthenticatedUser user) {
        return approvals.get(organizationId, approvalId, user);
    }

    @PostMapping(path = "/{approvalId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "approveToolAction",
            summary = "Approve this one tool call (never future ones). Refused when expired, decided, altered or no longer permitted")
    public ApprovalView approve(@PathVariable UUID organizationId, @PathVariable UUID approvalId, AuthenticatedUser user,
            @Valid @RequestBody ApproveRequest request) {
        return approvals.approve(organizationId, approvalId, user, request.argumentsHash());
    }

    @PostMapping(path = "/{approvalId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "rejectToolAction", summary = "Reject this tool call: it is never executed")
    public ApprovalView reject(@PathVariable UUID organizationId, @PathVariable UUID approvalId, AuthenticatedUser user,
            @Valid @RequestBody RejectRequest request) {
        return approvals.reject(organizationId, approvalId, user, request.reason());
    }
}
