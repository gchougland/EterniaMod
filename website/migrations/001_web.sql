CREATE SCHEMA IF NOT EXISTS eternia_web;
CREATE TABLE IF NOT EXISTS eternia_web.sessions (sid varchar PRIMARY KEY, sess json NOT NULL, expire timestamp(6) NOT NULL);
CREATE INDEX IF NOT EXISTS sessions_expire_idx ON eternia_web.sessions(expire);
CREATE TABLE IF NOT EXISTS eternia_web.content_revisions (key uuid PRIMARY KEY, content_id text NOT NULL, revision integer NOT NULL, document jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(content_id,revision));
CREATE TABLE IF NOT EXISTS eternia_web.render_jobs (id uuid PRIMARY KEY, document jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());

