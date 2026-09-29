alter table chat_conversations add column handoff_waiting_since timestamp with time zone;
alter table chat_conversations add column handoff_wait_reminder_sent_at timestamp with time zone;
alter table chat_conversations add column handoff_contact_prompt_sent_at timestamp with time zone;
alter table chat_conversations add column handoff_human_reply_at timestamp with time zone;

create index idx_chat_conversations_handoff_waiting
    on chat_conversations(state, handoff_waiting_since)
    where handoff_human_reply_at is null;
