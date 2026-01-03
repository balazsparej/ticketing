package balazs.parej.ticketing.exception;

public class TicketAlreadyAssignedException extends RuntimeException {
    public TicketAlreadyAssignedException(String assigneeId) {
        super("Ticket is already assigned to: " + assigneeId);
    }
}