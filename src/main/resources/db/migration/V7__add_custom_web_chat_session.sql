-- Live Chat conversations retain a NULL token. Only the first-party Custom
-- Channel client receives an opaque, high-entropy session token.
alter table chat_conversations add column web_chat_session_token varchar(128);

create unique index uk_chat_conversations_web_chat_session
    on chat_conversations(web_chat_session_token)
    where web_chat_session_token is not null;
