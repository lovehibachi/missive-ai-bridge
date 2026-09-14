package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FinReplyTurnGateTest {
    @Test
    void acceptsOnlyTheFirstReplyUntilTheNextCustomerTurn() {
        FinReplyTurnGate gate = new FinReplyTurnGate();

        assertTrue(gate.claimFirstReply("fin-1"));
        assertFalse(gate.claimFirstReply("fin-1"));

        gate.reset("fin-1");

        assertTrue(gate.claimFirstReply("fin-1"));
    }
}
