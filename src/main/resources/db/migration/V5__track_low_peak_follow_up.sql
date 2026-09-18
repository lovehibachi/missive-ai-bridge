alter table fin_sessions add column low_peak_follow_up_sent_at timestamp with time zone;

create index idx_fin_sessions_low_peak_follow_up
    on fin_sessions(status, first_reply_sent_at)
    where low_peak_follow_up_sent_at is null;
