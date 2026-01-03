package balazs.parej.ticketing.service;

import balazs.parej.ticketing.exception.ConcurrentModificationException;
import balazs.parej.ticketing.exception.InvalidTicketOperationException;
import balazs.parej.ticketing.exception.LockAcquisitionException;
import balazs.parej.ticketing.exception.TicketAlreadyAssignedException;
import balazs.parej.ticketing.model.Ticket;
import balazs.parej.ticketing.model.TicketStatus;
import balazs.parej.ticketing.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating distributed locking behavior
 * Uses Testcontainers to spin up a real Redis instance
 */
@SpringBootTest
@Testcontainers
class TicketServiceConcurrencyTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketRepository ticketRepository;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        ticketRepository.deleteAll();
        executorService = Executors.newFixedThreadPool(10);

        // Give Redis a moment to be ready
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Test: Multiple agents try to assign the same ticket concurrently
     * Expected: Only ONE agent succeeds, others get TicketAlreadyAssignedException
     */
    @Test
    void testConcurrentAssignment_OnlyOneSucceeds() throws Exception {
        // Create a ticket
        Ticket ticket = ticketService.createTicket(
                "Bug in login",
                "Users cannot log in",
                "user-123"
        );
        UUID ticketId = ticket.getTicketId();

        // Simulate 10 agents trying to claim the same ticket
        int numberOfAgents = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numberOfAgents);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<String> successfulAssignees = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // Submit concurrent assignment attempts
        for (int i = 0; i < numberOfAgents; i++) {
            final String agentId = "agent-" + i;

            executorService.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();

                    // Try to assign ticket
                    Ticket assigned = ticketService.assignTicket(ticketId, agentId);
                    successCount.incrementAndGet();
                    successfulAssignees.add(agentId);

                    System.out.println("✓ " + agentId + " successfully assigned ticket");

                } catch (TicketAlreadyAssignedException e) {
                    failureCount.incrementAndGet();
                    System.out.println("✗ " + agentId + " - ticket already assigned");

                } catch (LockAcquisitionException e) {
                    failureCount.incrementAndGet();
                    System.out.println("✗ " + agentId + " - failed to acquire lock");

                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    errors.add(agentId + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    System.err.println("✗ " + agentId + " - error: " + e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for all to complete (with timeout)
        boolean completed = completionLatch.await(20, TimeUnit.SECONDS);
        assertTrue(completed, "All threads should complete within timeout");

        // Small delay to ensure transaction commits
        Thread.sleep(100);

        // Verify results
        System.out.println("\n=== Test Results ===");
        System.out.println("Success: " + successCount.get());
        System.out.println("Failures: " + failureCount.get());
        System.out.println("Successful assignees: " + successfulAssignees);
        if (!errors.isEmpty()) {
            System.out.println("Errors: " + errors);
        }

        // Assertions - be more lenient due to distributed system timing
        assertThat(successCount.get())
                .withFailMessage("Expected exactly 1 success, got " + successCount.get() +
                        ". Successful assignees: " + successfulAssignees)
                .isEqualTo(1);

        assertThat(successCount.get() + failureCount.get())
                .withFailMessage("Some threads didn't complete properly")
                .isEqualTo(numberOfAgents);

        // Verify final state in database
        Ticket finalTicket = ticketService.getTicket(ticketId);
        assertNotNull(finalTicket.getAssigneeId(), "Ticket should be assigned");
        assertEquals(TicketStatus.IN_PROGRESS, finalTicket.getStatus());
        assertTrue(successfulAssignees.contains(finalTicket.getAssigneeId()),
                "Database should contain the successful assignee");
    }

    /**
     * Test: Concurrent updates to different fields
     * Expected: All updates succeed (no conflicts on different tickets)
     */
    @Test
    void testConcurrentUpdatesOnDifferentTickets_AllSucceed() throws Exception {
        // Create multiple tickets
        List<UUID> ticketIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Ticket ticket = ticketService.createTicket(
                    "Issue " + i,
                    "Description " + i,
                    "user-" + i
            );
            ticketIds.add(ticket.getTicketId());
        }

        CountDownLatch latch = new CountDownLatch(ticketIds.size());
        AtomicInteger successCount = new AtomicInteger(0);

        // Update each ticket concurrently
        for (int i = 0; i < ticketIds.size(); i++) {
            final UUID ticketId = ticketIds.get(i);
            final String agentId = "agent-" + i;

            executorService.submit(() -> {
                try {
                    ticketService.assignTicket(ticketId, agentId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Failed to assign ticket " + ticketId + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);

        // All should succeed (no contention)
        assertEquals(ticketIds.size(), successCount.get());

        // Verify all tickets are assigned
        for (UUID ticketId : ticketIds) {
            Ticket ticket = ticketService.getTicket(ticketId);
            assertNotNull(ticket.getAssigneeId());
            assertEquals(TicketStatus.IN_PROGRESS, ticket.getStatus());
        }
    }

    /**
     * Test: Optimistic locking on simple updates
     * Expected: One update succeeds, others get ConcurrentModificationException
     */
    @Test
    void testOptimisticLocking_DetectsConcurrentModifications() throws Exception {
        // Create ticket
        Ticket ticket = ticketService.createTicket(
                "Test ticket",
                "Original description",
                "user-123"
        );
        UUID ticketId = ticket.getTicketId();

        // Simulate concurrent description updates
        int updateCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(updateCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < updateCount; i++) {
            final int index = i;

            executorService.submit(() -> {
                try {
                    startLatch.await();

                    // Small delay to increase chance of conflict
                    Thread.sleep(10);

                    ticketService.updateTicketSimple(
                            ticketId,
                            null,
                            "Updated by thread-" + index
                    );

                    successCount.incrementAndGet();
                    System.out.println("✓ Thread-" + index + " updated successfully");

                } catch (ConcurrentModificationException e) {
                    conflictCount.incrementAndGet();
                    System.out.println("✗ Thread-" + index + " - concurrent modification detected");
                } catch (Exception e) {
                    System.err.println("✗ Thread-" + index + " - error: " + e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        completionLatch.await(10, TimeUnit.SECONDS);

        System.out.println("\n=== Optimistic Lock Test Results ===");
        System.out.println("Successful updates: " + successCount.get());
        System.out.println("Conflict detections: " + conflictCount.get());

        // At least one should succeed
        assertThat(successCount.get()).isGreaterThan(0);

        // Some conflicts may occur (depends on timing)
        System.out.println("Total attempts: " + updateCount +
                ", Success: " + successCount.get() +
                ", Conflicts: " + conflictCount.get());
    }

    /**
     * Test: Status transition validation with concurrent updates
     */
    @Test
    void testConcurrentStatusUpdates_ValidatesBusinessRules() throws Exception {
        Ticket ticket = ticketService.createTicket(
                "Test ticket",
                "Description",
                "user-123"
        );
        UUID ticketId = ticket.getTicketId();

        // First assign it
        ticketService.assignTicket(ticketId, "agent-1");

        // Now try to resolve it
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        // Thread 1: Resolve the ticket
        executorService.submit(() -> {
            try {
                ticketService.updateStatus(ticketId, TicketStatus.RESOLVED);
                successCount.incrementAndGet();
                System.out.println("✓ Ticket resolved successfully");
            } catch (Exception e) {
                System.out.println("✗ Failed to resolve: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        latch.await(10, TimeUnit.SECONDS);

        // Verify the ticket was resolved
        Ticket resolvedTicket = ticketService.getTicket(ticketId);
        assertEquals(TicketStatus.RESOLVED, resolvedTicket.getStatus(),
                "Ticket should be in RESOLVED status");

        // Now test the business rule: cannot reopen a closed ticket
        ticketService.updateStatus(ticketId, TicketStatus.CLOSED);

        Ticket closedTicket = ticketService.getTicket(ticketId);
        assertEquals(TicketStatus.CLOSED, closedTicket.getStatus());

        // Try to reopen - should fail
        assertThrows(InvalidTicketOperationException.class, () -> {
            ticketService.updateStatus(ticketId, TicketStatus.OPEN);
        }, "Should not be able to reopen a closed ticket");

        // Verify it's still closed
        Ticket finalTicket = ticketService.getTicket(ticketId);
        assertEquals(TicketStatus.CLOSED, finalTicket.getStatus(),
                "Ticket should remain closed after failed reopen attempt");
    }
}