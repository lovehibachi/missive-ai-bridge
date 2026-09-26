package com.lovehibachi.aichatbot.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bridge")
public class BridgeProperties {
    private Missive missive = new Missive();
    private Fin fin = new Fin();
    private Rules rules = new Rules();
    private Promotions promotions = new Promotions();
    private HandoffLinks handoffLinks = new HandoffLinks();
    private WebChat webChat = new WebChat();
    private Admin admin = new Admin();

    public Missive getMissive() { return missive; }
    public void setMissive(Missive missive) { this.missive = missive; }
    public Fin getFin() { return fin; }
    public void setFin(Fin fin) { this.fin = fin; }
    public Rules getRules() { return rules; }
    public void setRules(Rules rules) { this.rules = rules; }
    public Promotions getPromotions() { return promotions; }
    public void setPromotions(Promotions promotions) { this.promotions = promotions; }
    public HandoffLinks getHandoffLinks() { return handoffLinks; }
    public void setHandoffLinks(HandoffLinks handoffLinks) { this.handoffLinks = handoffLinks; }
    public WebChat getWebChat() { return webChat; }
    public void setWebChat(WebChat webChat) { this.webChat = webChat; }
    public Admin getAdmin() { return admin; }
    public void setAdmin(Admin admin) { this.admin = admin; }

    public static class Missive {
        private String apiBaseUrl = "https://public.missiveapp.com";
        private String finAiPat;
        private String webhookSecret;
        private String liveChatAccountId;
        private String customChannelAccountId;
        private String customChannelWebhookSecret;
        /** Recipient identity configured on the Missive Custom Channel account. */
        private String customChannelRecipientId;
        private String customChannelRecipientUsername;
        private String customChannelRecipientName = "Love Hibachi";
        private String organizationId;
        private String needHumanLabelId;
        private String handoffTeamId;
        private String aiTeamId;
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
        public String getFinAiPat() { return finAiPat; }
        public void setFinAiPat(String finAiPat) { this.finAiPat = finAiPat; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
        public String getLiveChatAccountId() { return liveChatAccountId; }
        public void setLiveChatAccountId(String liveChatAccountId) { this.liveChatAccountId = liveChatAccountId; }
        public String getCustomChannelAccountId() { return customChannelAccountId; }
        public void setCustomChannelAccountId(String value) { this.customChannelAccountId = value; }
        public String getCustomChannelWebhookSecret() { return customChannelWebhookSecret; }
        public void setCustomChannelWebhookSecret(String value) { this.customChannelWebhookSecret = value; }
        public String getCustomChannelRecipientId() { return customChannelRecipientId; }
        public void setCustomChannelRecipientId(String value) { this.customChannelRecipientId = value; }
        public String getCustomChannelRecipientUsername() { return customChannelRecipientUsername; }
        public void setCustomChannelRecipientUsername(String value) { this.customChannelRecipientUsername = value; }
        public String getCustomChannelRecipientName() { return customChannelRecipientName; }
        public void setCustomChannelRecipientName(String value) { this.customChannelRecipientName = value; }
        public String getOrganizationId() { return organizationId; }
        public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
        public String getNeedHumanLabelId() { return needHumanLabelId; }
        public void setNeedHumanLabelId(String needHumanLabelId) { this.needHumanLabelId = needHumanLabelId; }
        public String getHandoffTeamId() { return handoffTeamId; }
        public void setHandoffTeamId(String handoffTeamId) { this.handoffTeamId = handoffTeamId; }
        public String getAiTeamId() { return aiTeamId; }
        public void setAiTeamId(String aiTeamId) { this.aiTeamId = aiTeamId; }
    }

    public static class Fin {
        private String apiBaseUrl = "https://api.intercom.io";
        private String apiKey;
        private String webhookSecret;
        private String clientSecret;
        private String apiVersion = "2.16";
        private String conversationIdPrefix = "fin:missive";
        private WebhookRelay webhookRelay = new WebhookRelay();
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
        public String getConversationIdPrefix() { return conversationIdPrefix; }
        public void setConversationIdPrefix(String value) { conversationIdPrefix = value; }
        public WebhookRelay getWebhookRelay() { return webhookRelay; }
        public void setWebhookRelay(WebhookRelay value) { webhookRelay = value; }

        /** Optional local forwarding target for Fin callbacks belonging to another bridge instance. */
        public static class WebhookRelay {
            private String conversationIdPrefix;
            private String targetUrl;
            public String getConversationIdPrefix() { return conversationIdPrefix; }
            public void setConversationIdPrefix(String value) { conversationIdPrefix = value; }
            public String getTargetUrl() { return targetUrl; }
            public void setTargetUrl(String value) { targetUrl = value; }
        }
    }

    public static class Rules {
        private List<String> immediateHandoffPatterns = new ArrayList<String>();
        public List<String> getImmediateHandoffPatterns() { return immediateHandoffPatterns; }
        public void setImmediateHandoffPatterns(List<String> immediateHandoffPatterns) {
            this.immediateHandoffPatterns = immediateHandoffPatterns;
        }
    }

    public static class Promotions {
        private boolean lowPeakFollowUpEnabled = true;
        private long lowPeakFollowUpDelaySeconds = 60L;
        private long lowPeakFollowUpMaxAgeSeconds = 300L;
        private String lowPeakBookingUrl = "https://lovehibachi.com/booking-request/?utm_campaign=fin_low_peak";

        public boolean isLowPeakFollowUpEnabled() { return lowPeakFollowUpEnabled; }
        public void setLowPeakFollowUpEnabled(boolean value) { lowPeakFollowUpEnabled = value; }
        public long getLowPeakFollowUpDelaySeconds() { return lowPeakFollowUpDelaySeconds; }
        public void setLowPeakFollowUpDelaySeconds(long value) { lowPeakFollowUpDelaySeconds = value; }
        public long getLowPeakFollowUpMaxAgeSeconds() { return lowPeakFollowUpMaxAgeSeconds; }
        public void setLowPeakFollowUpMaxAgeSeconds(long value) { lowPeakFollowUpMaxAgeSeconds = value; }
        public String getLowPeakBookingUrl() { return lowPeakBookingUrl; }
        public void setLowPeakBookingUrl(String value) { lowPeakBookingUrl = value; }
    }

    public static class HandoffLinks {
        private String publicBaseUrl = "https://aiservices.letsgohibachi.com";
        private long ttlMinutes = 1440L;

        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String value) { publicBaseUrl = value; }
        public long getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(long value) { ttlMinutes = value; }
    }

    /** Credentials for deliberate operator actions; never expose this token to visitors. */
    /** Public browser-facing settings. No Fin or Missive credential belongs here. */
    public static class WebChat {
        private String allowedOrigin = "";
        private long longPollTimeoutMillis = 25000L;
        public String getAllowedOrigin() { return allowedOrigin; }
        public void setAllowedOrigin(String value) { allowedOrigin = value; }
        public long getLongPollTimeoutMillis() { return longPollTimeoutMillis; }
        public void setLongPollTimeoutMillis(long value) { longPollTimeoutMillis = value; }
    }

    public static class Admin {
        private String token;
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
}
