create table handoff_links (
    id varchar(36) primary key,
    chat_conversation_id varchar(36) not null references chat_conversations(id),
    token_hash varchar(64) not null,
    expires_at timestamp with time zone not null,
    used_at timestamp with time zone,
    created_at timestamp with time zone not null,
    constraint uk_handoff_link_token unique (token_hash)
);

create index idx_handoff_links_expiry on handoff_links(expires_at);
