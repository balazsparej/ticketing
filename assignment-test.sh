#!/bin/bash

BASE_URL="http://localhost:8080"
NUM_AGENTS=10

# Check if jq is installed
if ! command -v jq &> /dev/null; then
    echo "jq not found. Please install jq to run this script."
    exit 1
fi

# Step 1: Create a ticket
echo "Creating ticket..."
TICKET_JSON=$(curl -s -X POST "$BASE_URL/api/tickets" \
  -H "Content-Type: application/json" \
  -d '{
    "subject": "Login issue",
    "description": "User cannot log in",
    "userId": "user-123"
  }')

TICKET_ID=$(echo "$TICKET_JSON" | jq -r '.ticketId')

if [ -z "$TICKET_ID" ] || [ "$TICKET_ID" == "null" ]; then
    echo "Failed to create ticket. Response:"
    echo "$TICKET_JSON"
    exit 1
fi

echo "Ticket created: $TICKET_ID"

# Step 2: Concurrent assignment simulation
echo "Assigning ticket concurrently..."

for i in $(seq 0 $((NUM_AGENTS-1))); do
  AGENT="agent-$i"
  (
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE_URL/api/tickets/$TICKET_ID/assign" \
      -H "Content-Type: application/json" \
      -d "{\"assigneeId\": \"$AGENT\"}")

    if [ "$RESPONSE" -eq 200 ]; then
      echo "✓ $AGENT successfully assigned ticket"
    else
      echo "✗ $AGENT failed to assign ticket (HTTP $RESPONSE)"
    fi
  ) &
done

wait

# Step 3: Concurrent description updates
echo "Updating descriptions concurrently..."

for i in $(seq 0 $((NUM_AGENTS-1))); do
  AGENT="agent-$i"
  (
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE_URL/api/tickets/$TICKET_ID" \
      -H "Content-Type: application/json" \
      -d "{\"description\": \"Updated by $AGENT\"}")

    if [ "$RESPONSE" -eq 200 ]; then
      echo "✓ $AGENT updated description"
    else
      echo "✗ $AGENT failed to update description (HTTP $RESPONSE)"
    fi
  ) &
done

wait
echo "All done!"