alter table fin_sessions add column first_reply_sent_at timestamp with time zone;

create index idx_fin_sessions_first_reply_sent
    on fin_sessions(fin_conversation_id, first_reply_sent_at);
