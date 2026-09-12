package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class HardRuleEngineTest {
    @Test
    void onlyMatchesConfiguredLiteralSafetyRules() {
        BridgeProperties properties = new BridgeProperties();
        properties.getRules().setImmediateHandoffPatterns(Collections.singletonList("(?i)\\bfood poisoning\\b"));
        HardRuleEngine engine = new HardRuleEngine(properties);
        assertNotNull(engine.matchingRule("I got food poisoning after my order"));
        assertNull(engine.matchingRule("Can you tell me the menu?"));
    }
}
