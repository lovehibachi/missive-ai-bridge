package com.lovehibachi.aichatbot.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bridge")
public class BridgeProperties {
    private Missive missive = new Missive();
    private Fin fin = new Fin();
    private Rules rules = new Rules();

    public Missive getMissive() { return missive; }
    public void setMissive(Missive missive) { this.missive = missive; }
    public Fin getFin() { return fin; }
    public void setFin(Fin fin) { this.fin = fin; }
    public Rules getRules() { return rules; }
    public void setRules(Rules rules) { this.rules = rules; }

    public static class Missive {
        private String apiBaseUrl = "https://public.missiveapp.com";
        private String finAiPat;
        private String webhookSecret;
        private String liveChatAccountId;
        private String organizationId;
        private String needHumanLabelId;
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
        public String getFinAiPat() { return finAiPat; }
        public void setFinAiPat(String finAiPat) { this.finAiPat = finAiPat; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
        public String getLiveChatAccountId() { return liveChatAccountId; }
        public void setLiveChatAccountId(String liveChatAccountId) { this.liveChatAccountId = liveChatAccountId; }
        public String getOrganizationId() { return organizationId; }
        public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
        public String getNeedHumanLabelId() { return needHumanLabelId; }
        public void setNeedHumanLabelId(String needHumanLabelId) { this.needHumanLabelId = needHumanLabelId; }
    }

    public static class Fin {
        private String apiBaseUrl = "https://api.intercom.io";
        private String apiKey;
        private String webhookSecret;
        private String clientSecret;
        private String apiVersion = "2.16";
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    }

    public static class Rules {
        private List<String> immediateHandoffPatterns = new ArrayList<String>();
        public List<String> getImmediateHandoffPatterns() { return immediateHandoffPatterns; }
        public void setImmediateHandoffPatterns(List<String> immediateHandoffPatterns) {
            this.immediateHandoffPatterns = immediateHandoffPatterns;
        }
    }
}
