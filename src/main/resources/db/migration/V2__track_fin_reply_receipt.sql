alter table fin_sessions add column reply_received_at timestamp with time zone;

-- A deployment can occur after a reply was buffered but before the new receipt
-- timestamp existed. Treat those persisted buffers as stale candidates so they
-- are not held indefinitely.
update fin_sessions
set reply_received_at = created_at
where reply_buffer is not null and reply_received_at is null;

create index idx_fin_sessions_reply_finalization
    on fin_sessions(status, reply_received_at)
    where reply_buffer is not null;
