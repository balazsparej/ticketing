package balazs.parej.ticketing.dto;

import balazs.parej.ticketing.model.TicketStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public class DTOs {
    public record CreateTicketRequest(
            @NotBlank(message = "Subject is required")
            String subject,

            String description,

            @NotBlank(message = "User ID is required")
            String userId
    ) {}

    public record UpdateTicketRequest(
            String subject,
            String description
    ) {}

    public record AssignTicketRequest(
            @NotBlank(message = "Assignee ID is required")
            String assigneeId
    ) {}

    public record UpdateStatusRequest(
            @NotNull(message = "Status is required")
            TicketStatus status
    ) {}

// ==================== Response DTO ====================

    public record TicketResponse(
            UUID ticketId,
            String subject,
            String description,
            TicketStatus status,
            String userId,
            String assigneeId,
            Long version,
            Instant createdAt,
            Instant updatedAt
    ) {}
}