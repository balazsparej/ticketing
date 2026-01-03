package balazs.parej.ticketing.controller;

import balazs.parej.ticketing.dto.DTOs;
import balazs.parej.ticketing.model.Ticket;
import balazs.parej.ticketing.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    
    private final TicketService ticketService;
    
    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }
    
    /**
     * Create a new ticket
     * POST /api/tickets
     */
    @PostMapping
    public ResponseEntity<DTOs.TicketResponse> createTicket(@Valid @RequestBody DTOs.CreateTicketRequest request) {
        Ticket ticket = ticketService.createTicket(
            request.subject(),
            request.description(),
            request.userId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(ticket));
    }
    
    /**
     * Get ticket by ID
     * GET /api/tickets/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<DTOs.TicketResponse> getTicket(@PathVariable UUID id) {
        Ticket ticket = ticketService.getTicket(id);
        return ResponseEntity.ok(toResponse(ticket));
    }
    
    /**
     * Get all tickets
     * GET /api/tickets
     */
    @GetMapping
    public ResponseEntity<List<DTOs.TicketResponse>> getAllTickets() {
        List<Ticket> tickets = ticketService.getAllTickets();

        List<DTOs.TicketResponse> response = tickets.stream()
            .map(this::toResponse)
            .toList();
        
        return ResponseEntity.ok(response);
    }

    /**
     * Update ticket (simple fields like subject/description)
     * PATCH /api/tickets/{id}
     */
    @PatchMapping("/{id}")
    public ResponseEntity<DTOs.TicketResponse> updateTicket(
        @PathVariable UUID id,
        @RequestBody DTOs.UpdateTicketRequest request
    ) {
        Ticket ticket = ticketService.updateTicketSimple(
            id,
            request.subject(),
            request.description()
        );
        return ResponseEntity.ok(toResponse(ticket));
    }
    
    /**
     * Assign ticket to an agent
     * PATCH /api/tickets/{id}/assign
     */
    @PatchMapping("/{id}/assign")
    public ResponseEntity<DTOs.TicketResponse> assignTicket(
        @PathVariable UUID id,
        @Valid @RequestBody DTOs.AssignTicketRequest request
    ) {
        Ticket ticket = ticketService.assignTicket(id, request.assigneeId());
        return ResponseEntity.ok(toResponse(ticket));
    }

    /**
     * Update ticket status
     * PATCH /api/tickets/{id}/status
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<DTOs.TicketResponse> updateStatus(
        @PathVariable UUID id,
        @Valid @RequestBody DTOs.UpdateStatusRequest request
    ) {
        Ticket ticket = ticketService.updateStatus(id, request.status());
        return ResponseEntity.ok(toResponse(ticket));
    }

    /**
     * Convert entity to DTO
     */
    private DTOs.TicketResponse toResponse(Ticket ticket) {
        return new DTOs.TicketResponse(
            ticket.getTicketId(),
            ticket.getSubject(),
            ticket.getDescription(),
            ticket.getStatus(),
            ticket.getUserId(),
            ticket.getAssigneeId(),
            ticket.getVersion(),
            ticket.getCreatedAt(),
            ticket.getUpdatedAt()
        );
    }
}