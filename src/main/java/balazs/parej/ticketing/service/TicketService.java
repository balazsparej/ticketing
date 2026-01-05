package balazs.parej.ticketing.service;

import balazs.parej.ticketing.exception.ConcurrentModificationException;
import balazs.parej.ticketing.exception.InvalidTicketOperationException;
import balazs.parej.ticketing.exception.LockAcquisitionException;
import balazs.parej.ticketing.exception.TicketAlreadyAssignedException;
import balazs.parej.ticketing.exception.TicketNotFoundException;
import balazs.parej.ticketing.model.Ticket;
import balazs.parej.ticketing.model.TicketStatus;
import balazs.parej.ticketing.repository.TicketRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    private static final String LOCK_PREFIX = "ticket:lock:";
    private static final long LOCK_WAIT_TIME = 5;  // seconds
    private static final long LOCK_LEASE_TIME = 30; // seconds

    private final TicketRepository ticketRepository;
    private final RedissonClient redissonClient;

    public TicketService(TicketRepository ticketRepository, RedissonClient redissonClient) {
        this.ticketRepository = ticketRepository;
        this.redissonClient = redissonClient;
    }

    /**
     * Create a new ticket (no locking needed - new entity)
     */
    @Transactional
    public Ticket createTicket(String subject, String description, String userId) {
        log.info("Creating ticket for user: {}", userId);
        Ticket ticket = new Ticket(subject, description, userId);
        return ticketRepository.save(ticket);
    }

    /**
     * Get ticket by ID
     */
    @Transactional(readOnly = true)
    public Ticket getTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException(ticketId));
    }

    /**
     * Get all tickets
     */
    @Transactional(readOnly = true)
    public List<Ticket> getAllTickets() {
        return ticketRepository.findAll();
    }

    /**
     * Update simple fields with optimistic locking only
     * Use for low-contention updates like description changes
     */
    @Transactional
    public Ticket updateTicketSimple(UUID ticketId, String subject, String description) {
        log.info("Updating ticket {} (optimistic locking)", ticketId);
        
        try {
            Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException(ticketId));
            
            if (subject != null) {
                ticket.setSubject(subject);
            }
            if (description != null) {
                ticket.setDescription(description);
            }
            
            // JPA @Version will automatically check for concurrent modifications
            return ticketRepository.save(ticket);
            
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic lock failure for ticket {}", ticketId);
            throw new ConcurrentModificationException(
                "Ticket was modified by another user. Please refresh and try again."
            );
        }
    }

    /**
     * Assign ticket to an agent with distributed locking
     * This prevents race conditions when multiple agents try to claim the same ticket
     */
    @Transactional
    public Ticket assignTicket(UUID ticketId, String assigneeId) {
        String lockKey = LOCK_PREFIX + ticketId;
        RLock lock = redissonClient.getLock(lockKey);

        log.info("Attempting to assign ticket {} to agent {}", ticketId, assigneeId);

        try {
            // Try to acquire lock with timeout
            boolean acquired = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);

            if (!acquired) {
                log.warn("Failed to acquire lock for ticket {}", ticketId);
                throw new LockAcquisitionException(ticketId);
            }

            log.info("Lock acquired for ticket {} by {}", ticketId, assigneeId);

            try {
                // Load ticket - now guaranteed fresh from database
                Ticket ticket = ticketRepository.findById(ticketId)
                        .orElseThrow(() -> new TicketNotFoundException(ticketId));

                // Business rule: Cannot assign if already assigned
                if (ticket.getAssigneeId() != null) {
                    log.warn("Ticket {} already assigned to {}", ticketId, ticket.getAssigneeId());
                    throw new TicketAlreadyAssignedException(ticket.getAssigneeId());
                }

                // Business rule: Cannot assign closed tickets
                if (ticket.getStatus() == TicketStatus.CLOSED) {
                    log.warn("Cannot assign closed ticket {}", ticketId);
                    throw new InvalidTicketOperationException("Cannot assign a closed ticket");
                }

                // Perform assignment
                ticket.setAssigneeId(assigneeId);
                ticket.setStatus(TicketStatus.IN_PROGRESS);

                Ticket saved = ticketRepository.save(ticket);
                log.info("Ticket {} successfully assigned to {}", ticketId, assigneeId);

                return saved;
                
            } catch (ObjectOptimisticLockingFailureException e) {
                log.error("Optimistic lock failure despite distributed lock for ticket {}", ticketId);
                throw new ConcurrentModificationException(
                    "Unexpected concurrent modification. Please try again."
                );
            } finally {
                // Always release the lock
                unlockSafely(lock, ticketId);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while acquiring lock for ticket {}", ticketId);
            throw new RuntimeException("Operation interrupted", e);
        }
    }

    /**
     * Update ticket status with distributed locking
     */
    @Transactional
    public Ticket updateStatus(UUID ticketId, TicketStatus newStatus) {
        String lockKey = LOCK_PREFIX + ticketId;
        RLock lock = redissonClient.getLock(lockKey);

        log.info("Attempting to update status of ticket {} to {}", ticketId, newStatus);

        try {
            boolean acquired = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);

            if (!acquired) {
                log.warn("Failed to acquire lock for ticket {}", ticketId);
                throw new LockAcquisitionException(ticketId);
            }

            log.info("Lock acquired for ticket {}", ticketId);

            try {
                Ticket ticket = ticketRepository.findById(ticketId)
                        .orElseThrow(() -> new TicketNotFoundException(ticketId));

                TicketStatus oldStatus = ticket.getStatus();

                // Business rules for status transitions
                validateStatusTransition(oldStatus, newStatus);

                ticket.setStatus(newStatus);

                Ticket saved = ticketRepository.save(ticket);
                log.info("Ticket {} status updated from {} to {}", ticketId, oldStatus, newStatus);

                return saved;
                
            } catch (ObjectOptimisticLockingFailureException e) {
                log.error("Optimistic lock failure for ticket {}", ticketId);
                throw new ConcurrentModificationException(
                    "Ticket was modified concurrently. Please try again."
                );
            } finally {
                unlockSafely(lock, ticketId);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while acquiring lock for ticket {}", ticketId);
            throw new RuntimeException("Operation interrupted", e);
        }
    }

    /**
     * Safe unlock method
     */
    private void unlockSafely(RLock lock, UUID ticketId) {
        try {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("Lock released for ticket {}", ticketId);
            }
        } catch (Exception e) {
            log.error("Error releasing lock for ticket {}", ticketId, e);
        }
    }

    /**
     * Validate status transitions according to business rules
     */
    private void validateStatusTransition(TicketStatus from, TicketStatus to) {
        if (from == TicketStatus.CLOSED && to != TicketStatus.CLOSED) {
            throw new InvalidTicketOperationException(
                    "Cannot reopen a closed ticket. Create a new ticket instead."
            );
        }

        if (from == TicketStatus.OPEN && to == TicketStatus.CLOSED) {
            throw new InvalidTicketOperationException(
                    "Tickets must be resolved before closing. Please resolve the ticket first."
            );
        }
    }
}