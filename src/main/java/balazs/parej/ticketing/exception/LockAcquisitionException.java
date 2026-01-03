package balazs.parej.ticketing.exception;

import java.util.UUID;

public class LockAcquisitionException extends RuntimeException {
    public LockAcquisitionException(String message) {
        super(message);
    }
    
    public LockAcquisitionException(UUID ticketId) {
        super("Could not acquire lock for ticket: " + ticketId + ". Another process is updating this ticket.");
    }
}