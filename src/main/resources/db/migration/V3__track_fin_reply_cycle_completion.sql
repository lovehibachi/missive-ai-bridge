alter table fin_sessions add column reply_cycle_completed_at timestamp with time zone;

create index idx_fin_sessions_reply_cycle_completion
    on fin_sessions(fin_conversation_id, reply_cycle_completed_at);
