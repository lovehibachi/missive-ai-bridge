package com.lovehibachi.aichatbot.service;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.FinSession;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class FinClientTest {
    @Test
    void sendsExternalHelpdeskSettingOnFinStart() {
        BridgeProperties properties = new BridgeProperties();
        properties.getFin().setApiBaseUrl("https://fin.example.test");
        properties.getFin().setApiKey("test-key");
        properties.getFin().setApiVersion("2.16");
        properties.getFin().setFollowUpQuestions(false);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo("https://fin.example.test/fin/start"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Intercom-Version", "2.16"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"follow_up_questions\":false")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        conversation.setFinVisitorId("visitor-1");
        FinSession session = new FinSession();
        session.setFinConversationId("fin-1");

        new FinClient(restTemplate, properties).start(session, conversation, "Question", Collections.emptyList());

        server.verify();
    }
}
