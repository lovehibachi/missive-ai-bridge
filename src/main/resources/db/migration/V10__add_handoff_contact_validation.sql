alter table chat_conversations
    add column handoff_contact_attempts integer not null default 0;
alter table chat_conversations
    add column handoff_contact_completed_at timestamp with time zone;
