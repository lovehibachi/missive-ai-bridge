package com.lovehibachi.aichatbot.service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Keeps only pending long-poll requests in memory. Chat history remains in
 * PostgreSQL, so an application restart merely ends a poll and the browser
 * reconnects; it never loses a message.
 */
@Component
public class WebChatNotifier {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Waiter>> waiters =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Waiter>>();

    public void waitForMessage(final String sessionToken, DeferredResult<?> result, Runnable onMessage) {
        final Waiter waiter = new Waiter(result, onMessage);
        final CopyOnWriteArrayList<Waiter> sessionWaiters = waiters.computeIfAbsent(sessionToken,
                ignored -> new CopyOnWriteArrayList<Waiter>());
        sessionWaiters.add(waiter);
        result.onCompletion(() -> remove(sessionToken, waiter));
    }

    public void notifyMessage(String sessionToken) {
        List<Waiter> sessionWaiters = waiters.remove(sessionToken);
        if (sessionWaiters == null) { return; }
        for (Waiter waiter : sessionWaiters) { waiter.onMessage.run(); }
    }

    private void remove(String sessionToken, Waiter result) {
        CopyOnWriteArrayList<Waiter> sessionWaiters = waiters.get(sessionToken);
        if (sessionWaiters == null) { return; }
        sessionWaiters.remove(result);
        if (sessionWaiters.isEmpty()) { waiters.remove(sessionToken, sessionWaiters); }
    }

    private static class Waiter {
        private final DeferredResult<?> result;
        private final Runnable onMessage;
        Waiter(DeferredResult<?> result, Runnable onMessage) { this.result = result; this.onMessage = onMessage; }
    }
}
