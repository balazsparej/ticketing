package balazs.parej.ticketing.exception;

public class InvalidTicketOperationException extends RuntimeException {
    public InvalidTicketOperationException(String message) {
        super(message);
    }
}