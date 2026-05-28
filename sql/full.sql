-- DROP SCHEMA challengesphere;

CREATE SCHEMA challengesphere AUTHORIZATION mihirdalal;

-- DROP SEQUENCE challengesphere.challenge_invites_id_seq;

CREATE SEQUENCE challengesphere.challenge_invites_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.challenges_id_seq;

CREATE SEQUENCE challengesphere.challenges_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.comments_id_seq;

CREATE SEQUENCE challengesphere.comments_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.follows_id_seq;

CREATE SEQUENCE challengesphere.follows_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.glories_id_seq;

CREATE SEQUENCE challengesphere.glories_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.invite_usages_id_seq;

CREATE SEQUENCE challengesphere.invite_usages_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.leaderboard_entries_id_seq;

CREATE SEQUENCE challengesphere.leaderboard_entries_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.moderation_flags_id_seq;

CREATE SEQUENCE challengesphere.moderation_flags_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.notifications_id_seq;

CREATE SEQUENCE challengesphere.notifications_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.reports_id_seq;

CREATE SEQUENCE challengesphere.reports_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.submissions_id_seq;

CREATE SEQUENCE challengesphere.submissions_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.user_blocks_id_seq;

CREATE SEQUENCE challengesphere.user_blocks_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.user_challenges_id_seq;

CREATE SEQUENCE challengesphere.user_challenges_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.user_preferences_id_seq;

CREATE SEQUENCE challengesphere.user_preferences_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.user_reports_id_seq;

CREATE SEQUENCE challengesphere.user_reports_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;
-- DROP SEQUENCE challengesphere.users_id_seq;

CREATE SEQUENCE challengesphere.users_id_seq
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    START 1
    CACHE 1
    NO CYCLE;-- challengesphere.flyway_schema_history definition

-- Drop table

-- DROP TABLE challengesphere.flyway_schema_history;

CREATE TABLE challengesphere.flyway_schema_history (
                                                       installed_rank int4 NOT NULL,
                                                       "version" varchar(50) NULL,
                                                       description varchar(200) NOT NULL,
                                                       "type" varchar(20) NOT NULL,
                                                       script varchar(1000) NOT NULL,
                                                       checksum int4 NULL,
                                                       installed_by varchar(100) NOT NULL,
                                                       installed_on timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                       execution_time int4 NOT NULL,
                                                       success bool NOT NULL,
                                                       CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
);


-- challengesphere.users definition

-- Drop table

-- DROP TABLE challengesphere.users;

CREATE TABLE challengesphere.users (
                                       id bigserial NOT NULL,
                                       email_id varchar(255) NOT NULL,
                                       phone varchar(20) NULL,
                                       username varchar(50) NOT NULL,
                                       hashed_password varchar(255) NULL,
                                       created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
                                       genuineness_score int4 DEFAULT 100 NULL,
                                       active bool DEFAULT true NULL,
                                       challenges_created int4 DEFAULT 0 NULL,
                                       challenges_accepted int4 DEFAULT 0 NULL,
                                       followers int4 DEFAULT 0 NULL,
                                       "following" int4 DEFAULT 0 NULL,
                                       profile_pic_url text NULL,
                                       premium_user int4 DEFAULT 0 NULL,
                                       notifications int4 DEFAULT 1 NULL,
                                       bio text NULL,
                                       date_of_birth date NULL,
                                       age int4 NULL,
                                       google_id varchar(255) NULL,
                                       banned_at timestamptz NULL,
                                       ban_reason varchar(512) NULL,
                                       last_login_at timestamptz NULL,
                                       CONSTRAINT users_email_id_key UNIQUE (email_id),
                                       CONSTRAINT users_google_id_key UNIQUE (google_id),
                                       CONSTRAINT users_phone_key UNIQUE (phone),
                                       CONSTRAINT users_pkey PRIMARY KEY (id),
                                       CONSTRAINT users_username_key UNIQUE (username)
);
CREATE INDEX idx_users_last_login_at ON challengesphere.users USING btree (last_login_at DESC) WHERE (last_login_at IS NOT NULL);


-- challengesphere.challenges definition

-- Drop table

-- DROP TABLE challengesphere.challenges;

CREATE TABLE challengesphere.challenges (
                                            id bigserial NOT NULL,
                                            hashtags text NULL,
                                            categories text NULL,
                                            title varchar(255) NOT NULL,
                                            description text NOT NULL,
                                            benefits text NULL,
                                            private bool DEFAULT false NULL,
                                            start_date date NOT NULL,
                                            active bool DEFAULT true NULL,
                                            created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
                                            creator_id int8 NOT NULL,
                                            current_owner_id int8 NOT NULL,
                                            number_of_days int4 NOT NULL,
                                            cover_photo_urls text NULL,
                                            CONSTRAINT challenges_pkey PRIMARY KEY (id),
                                            CONSTRAINT challenges_creator_id_fkey FOREIGN KEY (creator_id) REFERENCES challengesphere.users(id),
                                            CONSTRAINT challenges_current_owner_id_fkey FOREIGN KEY (current_owner_id) REFERENCES challengesphere.users(id)
);


-- challengesphere.follows definition

-- Drop table

-- DROP TABLE challengesphere.follows;

CREATE TABLE challengesphere.follows (
                                         id bigserial NOT NULL,
                                         follower_id int8 NOT NULL,
                                         following_id int8 NOT NULL,
                                         created_at timestamp DEFAULT CURRENT_TIMESTAMP NULL,
                                         active bool DEFAULT true NOT NULL,
                                         CONSTRAINT follows_pkey PRIMARY KEY (id),
                                         CONSTRAINT uk_follows UNIQUE (follower_id, following_id),
                                         CONSTRAINT follows_follower_id_fkey FOREIGN KEY (follower_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE,
                                         CONSTRAINT follows_following_id_fkey FOREIGN KEY (following_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE INDEX idx_follows_follower_active ON challengesphere.follows USING btree (follower_id, active);
CREATE INDEX idx_follows_following_active ON challengesphere.follows USING btree (following_id, active);


-- challengesphere.leaderboard_entries definition

-- Drop table

-- DROP TABLE challengesphere.leaderboard_entries;

CREATE TABLE challengesphere.leaderboard_entries (
                                                     id bigserial NOT NULL,
                                                     challenge_id int8 NOT NULL,
                                                     user_id int8 NOT NULL,
                                                     score float8 DEFAULT 0 NOT NULL,
                                                     current_rank int4 NULL,
                                                     previous_rank int4 NULL,
                                                     submission_count int4 DEFAULT 0 NULL,
                                                     glory_points int4 DEFAULT 0 NULL,
                                                     streak_days int4 DEFAULT 0 NULL,
                                                     best_score float8 DEFAULT 0 NULL,
                                                     last_submission_at timestamp NULL,
                                                     first_submission_at timestamp NULL,
                                                     status varchar(20) DEFAULT 'ACTIVE'::character varying NULL,
                                                     created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                     updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                     CONSTRAINT leaderboard_entries_pkey PRIMARY KEY (id),
                                                     CONSTRAINT uk_leaderboard_challenge_user UNIQUE (challenge_id, user_id),
                                                     CONSTRAINT leaderboard_entries_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
                                                     CONSTRAINT leaderboard_entries_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE INDEX idx_leaderboard_challenge_score ON challengesphere.leaderboard_entries USING btree (challenge_id, score DESC);


-- challengesphere.notifications definition

-- Drop table

-- DROP TABLE challengesphere.notifications;

CREATE TABLE challengesphere.notifications (
                                               id bigserial NOT NULL,
                                               user_id int8 NOT NULL,
                                               "type" varchar(500) NOT NULL,
                                               message text NOT NULL,
                                               action_object text NULL,
                                               sent_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                               read_at timestamptz NULL,
                                               CONSTRAINT notifications_pkey PRIMARY KEY (id),
                                               CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE INDEX idx_notifications_user_unread ON challengesphere.notifications USING btree (user_id) WHERE (read_at IS NULL);


-- challengesphere.reports definition

-- Drop table

-- DROP TABLE challengesphere.reports;

CREATE TABLE challengesphere.reports (
                                         id bigserial NOT NULL,
                                         post_id uuid NOT NULL,
                                         user_id int8 NOT NULL,
                                         reason varchar(100) NOT NULL,
                                         description text NULL,
                                         created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
                                         status varchar(20) DEFAULT 'PENDING'::character varying NULL,
                                         active bool DEFAULT true NULL,
                                         CONSTRAINT reports_pkey PRIMARY KEY (id),
                                         CONSTRAINT reports_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);


-- challengesphere.submissions definition

-- Drop table

-- DROP TABLE challengesphere.submissions;

CREATE TABLE challengesphere.submissions (
                                             id bigserial NOT NULL,
                                             user_id int8 NOT NULL,
                                             challenge_id int8 NOT NULL,
                                             day_number int4 NOT NULL,
                                             description varchar(2000) NOT NULL,
                                             media_urls text NULL,
                                             is_private bool DEFAULT false NOT NULL,
                                             submission_time timestamptz NOT NULL,
                                             created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                             updated_at timestamptz NULL,
                                             active bool DEFAULT true NOT NULL,
                                             glories_count int8 DEFAULT 0 NOT NULL,
                                             comments_count int8 DEFAULT 0 NOT NULL,
                                             "version" int4 DEFAULT 0 NOT NULL,
                                             CONSTRAINT submissions_day_number_check CHECK ((day_number > 0)),
                                             CONSTRAINT submissions_pkey PRIMARY KEY (id),
                                             CONSTRAINT uk_submission_day UNIQUE (user_id, challenge_id, day_number),
                                             CONSTRAINT submissions_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
                                             CONSTRAINT submissions_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE INDEX idx_submissions_challenge_public ON challengesphere.submissions USING btree (challenge_id, is_private, active);
CREATE INDEX idx_submissions_user_time ON challengesphere.submissions USING btree (user_id, submission_time DESC);


-- challengesphere.user_blocks definition

-- Drop table

-- DROP TABLE challengesphere.user_blocks;

CREATE TABLE challengesphere.user_blocks (
                                             id int8 GENERATED BY DEFAULT AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
                                             blocker_user_id int8 NOT NULL,
                                             blocked_user_id int8 NOT NULL,
                                             active bool DEFAULT true NOT NULL,
                                             created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                             updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                             CONSTRAINT chk_user_blocks_not_self CHECK ((blocker_user_id <> blocked_user_id)),
                                             CONSTRAINT uk_user_blocks_pair UNIQUE (blocker_user_id, blocked_user_id),
                                             CONSTRAINT user_blocks_pkey PRIMARY KEY (id),
                                             CONSTRAINT user_blocks_blocked_user_id_fkey FOREIGN KEY (blocked_user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE,
                                             CONSTRAINT user_blocks_blocker_user_id_fkey FOREIGN KEY (blocker_user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE INDEX idx_user_blocks_blocked_active ON challengesphere.user_blocks USING btree (blocked_user_id, active);
CREATE INDEX idx_user_blocks_blocker_active ON challengesphere.user_blocks USING btree (blocker_user_id, active);


-- challengesphere.user_challenges definition

-- Drop table

-- DROP TABLE challengesphere.user_challenges;

CREATE TABLE challengesphere.user_challenges (
                                                 id bigserial NOT NULL,
                                                 user_id int8 NOT NULL,
                                                 challenge_id int8 NOT NULL,
                                                 joined_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
                                                 completed bool DEFAULT false NULL,
                                                 completed_at timestamptz NULL,
                                                 active bool DEFAULT true NULL,
                                                 CONSTRAINT uk_user_challenge UNIQUE (user_id, challenge_id),
                                                 CONSTRAINT user_challenges_pkey PRIMARY KEY (id),
                                                 CONSTRAINT user_challenges_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
                                                 CONSTRAINT user_challenges_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);


-- challengesphere.user_preferences definition

-- Drop table

-- DROP TABLE challengesphere.user_preferences;

CREATE TABLE challengesphere.user_preferences (
                                                  user_id int8 NOT NULL,
                                                  push_notifications_enabled bool DEFAULT true NOT NULL,
                                                  email_notifications_enabled bool DEFAULT true NOT NULL,
                                                  notify_on_glory bool DEFAULT false NOT NULL,
                                                  notify_on_comment bool DEFAULT true NOT NULL,
                                                  notify_on_follow bool DEFAULT true NOT NULL,
                                                  notify_on_challenge_invite bool DEFAULT true NOT NULL,
                                                  notify_on_challenge_update bool DEFAULT true NOT NULL,
                                                  private_profile bool DEFAULT false NOT NULL,
                                                  private_submissions bool DEFAULT false NOT NULL,
                                                  show_on_leaderboard bool DEFAULT true NOT NULL,
                                                  allow_challenge_invites bool DEFAULT true NOT NULL,
                                                  dark_mode bool DEFAULT false NOT NULL,
                                                  "language" varchar(8) DEFAULT 'en'::character varying NOT NULL,
                                                  created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                  updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                  id bigserial NOT NULL,
                                                  CONSTRAINT user_preferences_pkey PRIMARY KEY (id),
                                                  CONSTRAINT user_preferences_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_user_preferences_user_id ON challengesphere.user_preferences USING btree (user_id);


-- challengesphere.user_reports definition

-- Drop table

-- DROP TABLE challengesphere.user_reports;

CREATE TABLE challengesphere.user_reports (
                                              id int8 GENERATED BY DEFAULT AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
                                              reporter_user_id int8 NOT NULL,
                                              reported_user_id int8 NOT NULL,
                                              reason varchar(128) NOT NULL,
                                              details text NULL,
                                              status varchar(32) DEFAULT 'OPEN'::character varying NOT NULL,
                                              created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                              CONSTRAINT chk_user_reports_not_self CHECK ((reporter_user_id <> reported_user_id)),
                                              CONSTRAINT user_reports_pkey PRIMARY KEY (id),
                                              CONSTRAINT user_reports_reported_user_id_fkey FOREIGN KEY (reported_user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE,
                                              CONSTRAINT user_reports_reporter_user_id_fkey FOREIGN KEY (reporter_user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE INDEX idx_user_reports_reported_status ON challengesphere.user_reports USING btree (reported_user_id, status, created_at DESC);
CREATE INDEX idx_user_reports_reporter_created ON challengesphere.user_reports USING btree (reporter_user_id, created_at DESC);


-- challengesphere.challenge_invites definition

-- Drop table

-- DROP TABLE challengesphere.challenge_invites;

CREATE TABLE challengesphere.challenge_invites (
                                                   id bigserial NOT NULL,
                                                   challenge_id int8 NOT NULL,
                                                   created_by int8 NOT NULL,
                                                   invite_code varchar(20) NOT NULL,
                                                   invite_url text NULL,
                                                   max_uses int4 NULL,
                                                   use_count int4 DEFAULT 0 NOT NULL,
                                                   active bool DEFAULT true NOT NULL,
                                                   expires_at timestamptz NULL,
                                                   created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                   CONSTRAINT challenge_invites_invite_code_key UNIQUE (invite_code),
                                                   CONSTRAINT challenge_invites_pkey PRIMARY KEY (id),
                                                   CONSTRAINT challenge_invites_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
                                                   CONSTRAINT challenge_invites_created_by_fkey FOREIGN KEY (created_by) REFERENCES challengesphere.users(id)
);


-- challengesphere."comments" definition

-- Drop table

-- DROP TABLE challengesphere."comments";

CREATE TABLE challengesphere."comments" (
                                            id bigserial NOT NULL,
                                            submission_id int8 NULL,
                                            post_id uuid NULL,
                                            user_id int8 NOT NULL,
                                            "content" text NOT NULL,
                                            created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
                                            updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
                                            active bool DEFAULT true NULL,
                                            CONSTRAINT comments_pkey PRIMARY KEY (id),
                                            CONSTRAINT comments_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES challengesphere.submissions(id) ON DELETE CASCADE,
                                            CONSTRAINT comments_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE INDEX idx_comments_submission_active ON challengesphere.comments USING btree (submission_id, active, created_at DESC);


-- challengesphere.glories definition

-- Drop table

-- DROP TABLE challengesphere.glories;

CREATE TABLE challengesphere.glories (
                                         id bigserial NOT NULL,
                                         submission_id int8 NOT NULL,
                                         user_id int8 NOT NULL,
                                         created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                         active bool DEFAULT true NOT NULL,
                                         ungloried bool DEFAULT false NOT NULL,
                                         CONSTRAINT glories_pkey PRIMARY KEY (id),
                                         CONSTRAINT uk_glories UNIQUE (submission_id, user_id),
                                         CONSTRAINT glories_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES challengesphere.submissions(id) ON DELETE CASCADE,
                                         CONSTRAINT glories_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE INDEX idx_glories_submission_active ON challengesphere.glories USING btree (submission_id, active);


-- challengesphere.invite_usages definition

-- Drop table

-- DROP TABLE challengesphere.invite_usages;

CREATE TABLE challengesphere.invite_usages (
                                               id bigserial NOT NULL,
                                               invite_id int8 NOT NULL,
                                               user_id int8 NOT NULL,
                                               used_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                               CONSTRAINT invite_usages_pkey PRIMARY KEY (id),
                                               CONSTRAINT uk_invite_usage UNIQUE (invite_id, user_id),
                                               CONSTRAINT invite_usages_invite_id_fkey FOREIGN KEY (invite_id) REFERENCES challengesphere.challenge_invites(id) ON DELETE CASCADE,
                                               CONSTRAINT invite_usages_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);


-- challengesphere.moderation_flags definition

-- Drop table

-- DROP TABLE challengesphere.moderation_flags;

CREATE TABLE challengesphere.moderation_flags (
                                                  id bigserial NOT NULL,
                                                  submission_id int8 NOT NULL,
                                                  user_id int8 NOT NULL,
                                                  category varchar(32) NOT NULL,
                                                  severity varchar(16) NOT NULL,
                                                  media_url text NULL,
                                                  raw_response text NULL,
                                                  created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                  reviewed_at timestamptz NULL,
                                                  reviewed_by int8 NULL,
                                                  resolution varchar(32) NULL,
                                                  CONSTRAINT moderation_flags_pkey PRIMARY KEY (id),
                                                  CONSTRAINT moderation_flags_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES challengesphere.users(id) ON DELETE SET NULL,
                                                  CONSTRAINT moderation_flags_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES challengesphere.submissions(id) ON DELETE CASCADE,
                                                  CONSTRAINT moderation_flags_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE
);
CREATE INDEX idx_moderation_flags_pending ON challengesphere.moderation_flags USING btree (created_at) WHERE (reviewed_at IS NULL);
CREATE INDEX idx_moderation_flags_user ON challengesphere.moderation_flags USING btree (user_id);
