create table chat_conversations (
    id varchar(36) primary key,
    missive_conversation_id varchar(128) not null unique,
    live_chat_account_id varchar(128) not null,
    fin_visitor_id varchar(255) not null,
    visitor_to_fields text not null,
    state varchar(32) not null,
    escalation_reason text,
    last_missive_message_id varchar(128),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table fin_sessions (
    id varchar(36) primary key,
    chat_conversation_id varchar(36) not null references chat_conversations(id),
    fin_conversation_id varchar(255) not null unique,
    cycle_number integer not null,
    status varchar(64) not null,
    reply_buffer text,
    completed_at timestamp with time zone,
    created_at timestamp with time zone not null
);

create index idx_fin_sessions_conversation on fin_sessions(chat_conversation_id, cycle_number desc);

create table chat_messages (
    id varchar(36) primary key,
    chat_conversation_id varchar(36) not null references chat_conversations(id),
    external_message_id varchar(255) not null unique,
    author varchar(16) not null,
    body text not null,
    created_at timestamp with time zone not null
);

create index idx_chat_messages_conversation on chat_messages(chat_conversation_id, created_at desc);

create table webhook_events (
    id varchar(36) primary key,
    provider varchar(32) not null,
    external_event_id varchar(255) not null,
    event_type varchar(64) not null,
    payload text not null,
    status varchar(32) not null,
    error_message text,
    created_at timestamp with time zone not null,
    processed_at timestamp with time zone,
    constraint uk_webhook_provider_event unique (provider, external_event_id)
);

create index idx_webhook_events_status on webhook_events(status, created_at);
