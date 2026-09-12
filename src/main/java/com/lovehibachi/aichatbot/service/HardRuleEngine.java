package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class HardRuleEngine {
    private final BridgeProperties properties;

    public HardRuleEngine(BridgeProperties properties) { this.properties = properties; }

    public String matchingRule(String message) {
        if (message == null) { return null; }
        List<String> patterns = properties.getRules().getImmediateHandoffPatterns();
        for (String expression : patterns) {
            if (Pattern.compile(expression).matcher(message).find()) {
                return "hard_rule:" + expression;
            }
        }
        return null;
    }
}
