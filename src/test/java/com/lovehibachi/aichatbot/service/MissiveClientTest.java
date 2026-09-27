package com.lovehibachi.aichatbot.service;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class MissiveClientTest {

    @Test
    void lowPeakPromotionUsesLegacyTextLinkForLiveChat() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://missive.example.test/v1/drafts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("fin_low_peak")))
                .andExpect(content().string(containsString("{{ link:https://lovehibachi.com/booking-request/?utm_campaign=fin_low_peak here }}")))
                .andExpect(content().string(not(containsString("<a href="))))
                .andExpect(content().string(not(containsString("Talk to a human"))))
                .andExpect(content().string(not(containsString("/handoff/"))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client(restTemplate).sendLowPeakFollowUp(conversation());

        server.verify();
    }

    @Test
    void lowPeakPromotionUsesHtmlLinkForCustomChannel() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://missive.example.test/v1/drafts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("fin_low_peak")))
                .andExpect(content().string(containsString("<a href=\\\"https://lovehibachi.com/booking-request/?utm_campaign=fin_low_peak\\\">here</a>")))
                .andExpect(content().string(not(containsString("{{ link:"))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client(restTemplate).sendLowPeakFollowUp(customChannelConversation());

        server.verify();
    }

    @Test
    void handoffAcknowledgementDoesNotContainAnotherHumanHandoffLink() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://missive.example.test/v1/drafts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("connecting you with a member of our team")))
                .andExpect(content().string(not(containsString("Talk to a human"))))
                .andExpect(content().string(not(containsString("/handoff/"))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client(restTemplate).sendHumanHandoffAcknowledgement(conversation());

        server.verify();
    }

    @Test
    void resumeAiMovesConversationAndRemovesHumanLabel() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://missive.example.test/v1/posts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"team\":\"ai-team-1\"")))
                .andExpect(content().string(containsString("\"force_team\":true")))
                .andExpect(content().string(containsString("\"remove_shared_labels\":[\"need-human-label-1\"]")))
                .andExpect(content().string(containsString("\"notification\":{\"title\":\"已恢复 AI 处理\"")))
                .andExpect(content().string(not(containsString("add_shared_labels"))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client(restTemplate).resumeAiHandling(conversation());

        server.verify();
    }

    @Test
    void receivesCustomChannelVisitorMessageWithoutExposingThePat() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://missive.example.test/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"messages\":{\"account\":\"custom-account-1\"")))
                .andExpect(content().string(containsString("\"account\":\"custom-account-1\"")))
                .andExpect(content().string(containsString("\"external_id\":\"client-1\"")))
                .andExpect(content().string(containsString("\"conversation\":\"existing-conversation-1\"")))
                .andExpect(content().string(not(containsString("test-token"))))
                .andRespond(withSuccess("{\"messages\":{\"id\":\"message-1\",\"conversation\":{\"id\":\"conversation-1\"}}}",
                        MediaType.APPLICATION_JSON));

        MissiveClient.CustomChannelMessageReceipt receipt = client(restTemplate).receiveCustomChannelMessage(
                "12345678901234567890123456789012", "Hello", "client-1", "existing-conversation-1");

        assertEquals("message-1", receipt.getMessageId());
        assertEquals("conversation-1", receipt.getConversationId());
        server.verify();
    }

    @Test
    void receivesCustomChannelMessageWhenMissiveReturnsDirectEntity() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://missive.example.test/v1/messages"))
                .andRespond(withSuccess("{\"messages\":{\"id\":\"message-2\",\"conversation\":\"conversation-2\"}}",
                        MediaType.APPLICATION_JSON));

        MissiveClient.CustomChannelMessageReceipt receipt = client(restTemplate).receiveCustomChannelMessage(
                "12345678901234567890123456789012", "Hello", "client-2", null);

        assertEquals("message-2", receipt.getMessageId());
        assertEquals("conversation-2", receipt.getConversationId());
        server.verify();
    }

    private MissiveClient client(RestTemplate restTemplate) {
        BridgeProperties properties = new BridgeProperties();
        properties.getMissive().setApiBaseUrl("https://missive.example.test");
        properties.getMissive().setFinAiPat("test-token");
        properties.getMissive().setOrganizationId("organization-1");
        properties.getMissive().setNeedHumanLabelId("need-human-label-1");
        properties.getMissive().setAiTeamId("ai-team-1");
        properties.getMissive().setCustomChannelAccountId("custom-account-1");
        properties.getMissive().setCustomChannelRecipientId("custom-recipient-1");
        properties.getMissive().setCustomChannelRecipientUsername("lovehibachi");
        properties.getPromotions().setLowPeakBookingUrl(
                "https://lovehibachi.com/booking-request/?utm_campaign=fin_low_peak");
        return new MissiveClient(restTemplate, new ObjectMapper(), properties);
    }

    private ChatConversation conversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-conversation-1");
        conversation.setLiveChatAccountId("live-chat-account-1");
        conversation.setVisitorToFields("[]");
        return conversation;
    }

    private ChatConversation customChannelConversation() {
        ChatConversation conversation = conversation();
        conversation.setLiveChatAccountId("custom-account-1");
        return conversation;
    }
}
