package balazs.parej.ticketing.exception;

import java.util.UUID;

public class TicketNotFoundException extends RuntimeException {
    public TicketNotFoundException(UUID ticketId) {
        super("Ticket not found with ID: " + ticketId);
    }
}