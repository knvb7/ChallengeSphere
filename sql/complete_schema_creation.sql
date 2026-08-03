--
-- PostgreSQL database dump
--

-- restrict H73DgsVuUeucTezzxaS1hUKzhCZFyrMmXmOQdNwQG05glnnMOepyPlBbWa6ozca

-- Dumped from database version 18.3 (Postgres.app)
-- Dumped by pg_dump version 18.3 (Postgres.app)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
-- transaction_timeout is only available on newer PostgreSQL servers.
-- Leave it out so this script can run from DBeaver against older targets.
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

ALTER TABLE IF EXISTS ONLY challengesphere.user_reports DROP CONSTRAINT IF EXISTS user_reports_reporter_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_reports DROP CONSTRAINT IF EXISTS user_reports_reported_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_preferences DROP CONSTRAINT IF EXISTS user_preferences_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_challenges DROP CONSTRAINT IF EXISTS user_challenges_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_challenges DROP CONSTRAINT IF EXISTS user_challenges_challenge_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_blocks DROP CONSTRAINT IF EXISTS user_blocks_blocker_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_blocks DROP CONSTRAINT IF EXISTS user_blocks_blocked_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.submissions DROP CONSTRAINT IF EXISTS submissions_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.submissions DROP CONSTRAINT IF EXISTS submissions_challenge_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.reports DROP CONSTRAINT IF EXISTS reports_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.push_device_tokens DROP CONSTRAINT IF EXISTS push_device_tokens_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.notifications DROP CONSTRAINT IF EXISTS notifications_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.moderation_flags DROP CONSTRAINT IF EXISTS moderation_flags_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.moderation_flags DROP CONSTRAINT IF EXISTS moderation_flags_submission_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.moderation_flags DROP CONSTRAINT IF EXISTS moderation_flags_reviewed_by_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.leaderboard_entries DROP CONSTRAINT IF EXISTS leaderboard_entries_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.leaderboard_entries DROP CONSTRAINT IF EXISTS leaderboard_entries_challenge_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.invite_usages DROP CONSTRAINT IF EXISTS invite_usages_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.invite_usages DROP CONSTRAINT IF EXISTS invite_usages_invite_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.glories DROP CONSTRAINT IF EXISTS glories_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.glories DROP CONSTRAINT IF EXISTS glories_submission_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.follows DROP CONSTRAINT IF EXISTS follows_following_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.follows DROP CONSTRAINT IF EXISTS follows_follower_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.reports DROP CONSTRAINT IF EXISTS fk_reports_submission;
ALTER TABLE IF EXISTS ONLY challengesphere.comments DROP CONSTRAINT IF EXISTS fk_comments_submission;
ALTER TABLE IF EXISTS ONLY challengesphere.comments DROP CONSTRAINT IF EXISTS comments_user_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.comments DROP CONSTRAINT IF EXISTS comments_submission_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.challenges DROP CONSTRAINT IF EXISTS challenges_current_owner_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.challenges DROP CONSTRAINT IF EXISTS challenges_creator_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.challenge_invites DROP CONSTRAINT IF EXISTS challenge_invites_created_by_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.challenge_invites DROP CONSTRAINT IF EXISTS challenge_invites_challenge_id_fkey;
ALTER TABLE IF EXISTS ONLY challengesphere.auth_tokens DROP CONSTRAINT IF EXISTS auth_tokens_user_id_fkey;
DROP INDEX IF EXISTS challengesphere.ux_user_preferences_user_id;
DROP INDEX IF EXISTS challengesphere.uk_submission_day_active;
DROP INDEX IF EXISTS challengesphere.idx_users_last_login_at;
DROP INDEX IF EXISTS challengesphere.idx_users_interests_active;
DROP INDEX IF EXISTS challengesphere.idx_user_reports_reporter_created;
DROP INDEX IF EXISTS challengesphere.idx_user_reports_reported_status;
DROP INDEX IF EXISTS challengesphere.idx_user_preferences_user_id;
DROP INDEX IF EXISTS challengesphere.idx_user_blocks_blocker_active;
DROP INDEX IF EXISTS challengesphere.idx_user_blocks_blocked_active;
DROP INDEX IF EXISTS challengesphere.idx_submissions_user_time;
DROP INDEX IF EXISTS challengesphere.idx_submissions_user_challenge_day;
DROP INDEX IF EXISTS challengesphere.idx_submissions_challenge_public;
DROP INDEX IF EXISTS challengesphere.idx_submissions_moderation_queue;
DROP INDEX IF EXISTS challengesphere.idx_reports_submission_id;
DROP INDEX IF EXISTS challengesphere.idx_push_device_tokens_user_enabled;
DROP INDEX IF EXISTS challengesphere.idx_push_device_tokens_enabled_timezone;
DROP INDEX IF EXISTS challengesphere.idx_notifications_user_unread;
DROP INDEX IF EXISTS challengesphere.idx_moderation_flags_user;
DROP INDEX IF EXISTS challengesphere.idx_moderation_flags_pending;
DROP INDEX IF EXISTS challengesphere.idx_leaderboard_challenge_score;
DROP INDEX IF EXISTS challengesphere.idx_invite_usages_invite;
DROP INDEX IF EXISTS challengesphere.idx_glories_submission_ungloried;
DROP INDEX IF EXISTS challengesphere.idx_glories_submission_active;
DROP INDEX IF EXISTS challengesphere.idx_follows_following_active;
DROP INDEX IF EXISTS challengesphere.idx_follows_follower_active;
DROP INDEX IF EXISTS challengesphere.idx_comments_submission_id;
DROP INDEX IF EXISTS challengesphere.idx_comments_submission_active;
DROP INDEX IF EXISTS challengesphere.idx_challenge_invites_code;
DROP INDEX IF EXISTS challengesphere.idx_challenge_invites_challenge;
DROP INDEX IF EXISTS challengesphere.idx_auth_tokens_user_id;
DROP INDEX IF EXISTS challengesphere.idx_auth_tokens_expires_at;
ALTER TABLE IF EXISTS ONLY challengesphere.users DROP CONSTRAINT IF EXISTS users_username_key;
ALTER TABLE IF EXISTS ONLY challengesphere.users DROP CONSTRAINT IF EXISTS users_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.users DROP CONSTRAINT IF EXISTS users_phone_key;
ALTER TABLE IF EXISTS ONLY challengesphere.users DROP CONSTRAINT IF EXISTS users_google_id_key;
ALTER TABLE IF EXISTS ONLY challengesphere.users DROP CONSTRAINT IF EXISTS users_email_id_key;
ALTER TABLE IF EXISTS ONLY challengesphere.user_reports DROP CONSTRAINT IF EXISTS user_reports_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_preferences DROP CONSTRAINT IF EXISTS user_preferences_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_challenges DROP CONSTRAINT IF EXISTS user_challenges_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_blocks DROP CONSTRAINT IF EXISTS user_blocks_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.user_challenges DROP CONSTRAINT IF EXISTS uk_user_challenge;
ALTER TABLE IF EXISTS ONLY challengesphere.user_blocks DROP CONSTRAINT IF EXISTS uk_user_blocks_pair;
ALTER TABLE IF EXISTS ONLY challengesphere.leaderboard_entries DROP CONSTRAINT IF EXISTS uk_leaderboard_challenge_user;
ALTER TABLE IF EXISTS ONLY challengesphere.invite_usages DROP CONSTRAINT IF EXISTS uk_invite_usage;
ALTER TABLE IF EXISTS ONLY challengesphere.glories DROP CONSTRAINT IF EXISTS uk_glories;
ALTER TABLE IF EXISTS ONLY challengesphere.follows DROP CONSTRAINT IF EXISTS uk_follows;
ALTER TABLE IF EXISTS ONLY challengesphere.submissions DROP CONSTRAINT IF EXISTS submissions_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.reports DROP CONSTRAINT IF EXISTS reports_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.push_device_tokens DROP CONSTRAINT IF EXISTS push_device_tokens_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.push_device_tokens DROP CONSTRAINT IF EXISTS push_device_tokens_expo_push_token_key;
ALTER TABLE IF EXISTS ONLY challengesphere.notifications DROP CONSTRAINT IF EXISTS notifications_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.moderation_flags DROP CONSTRAINT IF EXISTS moderation_flags_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.leaderboard_entries DROP CONSTRAINT IF EXISTS leaderboard_entries_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.invite_usages DROP CONSTRAINT IF EXISTS invite_usages_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.glories DROP CONSTRAINT IF EXISTS glories_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.follows DROP CONSTRAINT IF EXISTS follows_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.flyway_schema_history DROP CONSTRAINT IF EXISTS flyway_schema_history_pk;
ALTER TABLE IF EXISTS ONLY challengesphere.comments DROP CONSTRAINT IF EXISTS comments_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.challenges DROP CONSTRAINT IF EXISTS challenges_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.challenge_invites DROP CONSTRAINT IF EXISTS challenge_invites_pkey;
ALTER TABLE IF EXISTS ONLY challengesphere.challenge_invites DROP CONSTRAINT IF EXISTS challenge_invites_invite_code_key;
ALTER TABLE IF EXISTS ONLY challengesphere.auth_tokens DROP CONSTRAINT IF EXISTS auth_tokens_pkey;
ALTER TABLE IF EXISTS challengesphere.users ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.user_preferences ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.user_challenges ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.submissions ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.reports ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.push_device_tokens ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.moderation_flags ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.leaderboard_entries ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.invite_usages ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.glories ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.follows ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.comments ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.challenges ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS challengesphere.challenge_invites ALTER COLUMN id DROP DEFAULT;
DROP SEQUENCE IF EXISTS challengesphere.users_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.users_id_seq;
DROP TABLE IF EXISTS challengesphere.users;
DROP SEQUENCE IF EXISTS challengesphere.user_reports_id_seq;
DROP TABLE IF EXISTS challengesphere.user_reports;
DROP SEQUENCE IF EXISTS challengesphere.user_preferences_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.user_preferences_id_seq;
DROP TABLE IF EXISTS challengesphere.user_preferences;
DROP SEQUENCE IF EXISTS challengesphere.user_challenges_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.user_challenges_id_seq;
DROP TABLE IF EXISTS challengesphere.user_challenges;
DROP SEQUENCE IF EXISTS challengesphere.user_blocks_id_seq;
DROP TABLE IF EXISTS challengesphere.user_blocks;
DROP SEQUENCE IF EXISTS challengesphere.submissions_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.submissions_id_seq;
DROP TABLE IF EXISTS challengesphere.submissions;
DROP SEQUENCE IF EXISTS challengesphere.reports_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.reports_id_seq;
DROP TABLE IF EXISTS challengesphere.reports;
DROP SEQUENCE IF EXISTS challengesphere.push_device_tokens_id_seq;
DROP TABLE IF EXISTS challengesphere.push_device_tokens;
DROP SEQUENCE IF EXISTS challengesphere.notifications_id_seq1;
DROP TABLE IF EXISTS challengesphere.notifications;
DROP SEQUENCE IF EXISTS challengesphere.notifications_id_seq;
DROP SEQUENCE IF EXISTS challengesphere.moderation_flags_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.moderation_flags_id_seq;
DROP TABLE IF EXISTS challengesphere.moderation_flags;
DROP SEQUENCE IF EXISTS challengesphere.leaderboard_entries_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.leaderboard_entries_id_seq;
DROP TABLE IF EXISTS challengesphere.leaderboard_entries;
DROP SEQUENCE IF EXISTS challengesphere.invite_usages_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.invite_usages_id_seq;
DROP TABLE IF EXISTS challengesphere.invite_usages;
DROP SEQUENCE IF EXISTS challengesphere.glories_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.glories_id_seq;
DROP TABLE IF EXISTS challengesphere.glories;
DROP SEQUENCE IF EXISTS challengesphere.follows_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.follows_id_seq;
DROP TABLE IF EXISTS challengesphere.follows;
DROP TABLE IF EXISTS challengesphere.flyway_schema_history;
DROP SEQUENCE IF EXISTS challengesphere.comments_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.comments_id_seq;
DROP TABLE IF EXISTS challengesphere.comments;
DROP SEQUENCE IF EXISTS challengesphere.challenges_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.challenges_id_seq;
DROP TABLE IF EXISTS challengesphere.challenges;
DROP SEQUENCE IF EXISTS challengesphere.challenge_invites_id_seq1;
DROP SEQUENCE IF EXISTS challengesphere.challenge_invites_id_seq;
DROP TABLE IF EXISTS challengesphere.challenge_invites;
DROP TABLE IF EXISTS challengesphere.auth_tokens;
DROP SCHEMA IF EXISTS challengesphere;
--
-- Name: challengesphere; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA challengesphere;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: auth_tokens; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.auth_tokens (
                                             token_hash character varying(64) NOT NULL,
                                             token_type character varying(20) NOT NULL,
                                             user_id bigint NOT NULL,
                                             expires_at timestamp with time zone NOT NULL,
                                             created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: challenge_invites; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.challenge_invites (
                                                   id bigint NOT NULL,
                                                   challenge_id bigint NOT NULL,
                                                   created_by bigint NOT NULL,
                                                   invite_code character varying(20) NOT NULL,
                                                   invite_url text,
                                                   max_uses integer,
                                                   use_count integer DEFAULT 0 NOT NULL,
                                                   active boolean DEFAULT true NOT NULL,
                                                   expires_at timestamp with time zone,
                                                   created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: challenge_invites_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.challenge_invites_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenge_invites_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.challenge_invites_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenge_invites_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.challenge_invites_id_seq1 OWNED BY challengesphere.challenge_invites.id;


--
-- Name: challenges; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.challenges (
                                            id bigint NOT NULL,
                                            hashtags text,
                                            categories text,
                                            title character varying(255) NOT NULL,
                                            description text NOT NULL,
                                            benefits text,
                                            private boolean DEFAULT false,
                                            start_date date NOT NULL,
                                            active boolean DEFAULT true,
                                            created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
                                            creator_id bigint NOT NULL,
                                            current_owner_id bigint NOT NULL,
                                            number_of_days integer NOT NULL,
                                            cover_photo_urls text,
                                            challenge_mode character varying(20) DEFAULT 'DAILY'::character varying NOT NULL
);


--
-- Name: challenges_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.challenges_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenges_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.challenges_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenges_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.challenges_id_seq1 OWNED BY challengesphere.challenges.id;


--
-- Name: comments; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.comments (
                                          id bigint NOT NULL,
                                          submission_id bigint NOT NULL,
                                          user_id bigint NOT NULL,
                                          content text NOT NULL,
                                          created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
                                          updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
                                          active boolean DEFAULT true
);


--
-- Name: comments_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.comments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: comments_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.comments_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: comments_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.comments_id_seq1 OWNED BY challengesphere.comments.id;


--
-- Name: flyway_schema_history; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.flyway_schema_history (
                                                       installed_rank integer NOT NULL,
                                                       version character varying(50),
                                                       description character varying(200) NOT NULL,
                                                       type character varying(20) NOT NULL,
                                                       script character varying(1000) NOT NULL,
                                                       checksum integer,
                                                       installed_by character varying(100) NOT NULL,
                                                       installed_on timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                       execution_time integer NOT NULL,
                                                       success boolean NOT NULL
);


--
-- Name: follows; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.follows (
                                         id bigint NOT NULL,
                                         follower_id bigint NOT NULL,
                                         following_id bigint NOT NULL,
                                         created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
                                         active boolean DEFAULT true NOT NULL
);


--
-- Name: follows_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.follows_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: follows_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.follows_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: follows_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.follows_id_seq1 OWNED BY challengesphere.follows.id;


--
-- Name: glories; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.glories (
                                         id bigint NOT NULL,
                                         submission_id bigint NOT NULL,
                                         user_id bigint NOT NULL,
                                         created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                         active boolean DEFAULT true NOT NULL,
                                         ungloried boolean DEFAULT false NOT NULL,
                                         CONSTRAINT chk_glory_state CHECK ((NOT ((active = true) AND (ungloried = true))))
);


--
-- Name: glories_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.glories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: glories_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.glories_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: glories_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.glories_id_seq1 OWNED BY challengesphere.glories.id;


--
-- Name: invite_usages; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.invite_usages (
                                               id bigint NOT NULL,
                                               invite_id bigint NOT NULL,
                                               user_id bigint NOT NULL,
                                               used_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: invite_usages_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.invite_usages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: invite_usages_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.invite_usages_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: invite_usages_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.invite_usages_id_seq1 OWNED BY challengesphere.invite_usages.id;


--
-- Name: leaderboard_entries; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.leaderboard_entries (
                                                     id bigint NOT NULL,
                                                     challenge_id bigint NOT NULL,
                                                     user_id bigint NOT NULL,
                                                     score double precision DEFAULT 0 NOT NULL,
                                                     current_rank integer,
                                                     previous_rank integer,
                                                     submission_count integer DEFAULT 0,
                                                     glory_points integer DEFAULT 0,
                                                     streak_days integer DEFAULT 0,
                                                     best_score double precision DEFAULT 0,
                                                     last_submission_at timestamp without time zone,
                                                     first_submission_at timestamp without time zone,
                                                     status character varying(20) DEFAULT 'ACTIVE'::character varying,
                                                     created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                     updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: leaderboard_entries_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.leaderboard_entries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: leaderboard_entries_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.leaderboard_entries_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: leaderboard_entries_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.leaderboard_entries_id_seq1 OWNED BY challengesphere.leaderboard_entries.id;


--
-- Name: moderation_flags; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.moderation_flags (
                                                  id bigint NOT NULL,
                                                  submission_id bigint NOT NULL,
                                                  user_id bigint NOT NULL,
                                                  category character varying(32) NOT NULL,
                                                  severity character varying(16) NOT NULL,
                                                  media_url text,
                                                  raw_response text,
                                                  created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                  reviewed_at timestamp with time zone,
                                                  reviewed_by bigint,
                                                  resolution character varying(32)
);


--
-- Name: moderation_flags_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.moderation_flags_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: moderation_flags_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.moderation_flags_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: moderation_flags_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.moderation_flags_id_seq1 OWNED BY challengesphere.moderation_flags.id;


--
-- Name: notifications_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.notifications_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: notifications; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.notifications (
                                               id bigint DEFAULT nextval('challengesphere.notifications_id_seq'::regclass) NOT NULL,
                                               user_id bigint NOT NULL,
                                               type character varying(500) NOT NULL,
                                               message text NOT NULL,
                                               action_object text,
                                               sent_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                               read_at timestamp with time zone
);


--
-- Name: notifications_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.notifications_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: notifications_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.notifications_id_seq1 OWNED BY challengesphere.notifications.id;


--
-- Name: push_device_tokens; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.push_device_tokens (
                                                    id bigint NOT NULL,
                                                    user_id bigint NOT NULL,
                                                    expo_push_token character varying(255) NOT NULL,
                                                    platform character varying(32) DEFAULT 'unknown'::character varying NOT NULL,
                                                    timezone character varying(64) DEFAULT 'UTC'::character varying NOT NULL,
                                                    enabled boolean DEFAULT true NOT NULL,
                                                    last_seen_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                    last_daily_reminder_sent_date date,
                                                    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: push_device_tokens_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.push_device_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: push_device_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.push_device_tokens_id_seq OWNED BY challengesphere.push_device_tokens.id;


--
-- Name: reports; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.reports (
                                         id bigint NOT NULL,
                                         user_id bigint NOT NULL,
                                         reason character varying(100) NOT NULL,
                                         description text,
                                         created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
                                         status character varying(20) DEFAULT 'PENDING'::character varying,
                                         active boolean DEFAULT true,
                                         submission_id bigint NOT NULL
);


--
-- Name: reports_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.reports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: reports_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.reports_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: reports_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.reports_id_seq1 OWNED BY challengesphere.reports.id;


--
-- Name: submissions; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.submissions (
                                             id bigint NOT NULL,
                                             user_id bigint NOT NULL,
                                             challenge_id bigint NOT NULL,
                                             day_number integer NOT NULL,
                                             description text NOT NULL,
                                             media_urls text,
                                             is_private boolean DEFAULT false NOT NULL,
                                             submission_time timestamp with time zone NOT NULL,
                                             created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                             updated_at timestamp with time zone,
                                             active boolean DEFAULT true NOT NULL,
                                             moderation_status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
                                             moderation_started_at timestamp with time zone,
                                             moderated_at timestamp with time zone,
                                             moderation_error character varying(512),
                                             glories_count bigint DEFAULT 0 NOT NULL,
                                             comments_count bigint DEFAULT 0 NOT NULL,
                                             version integer DEFAULT 0 NOT NULL,
                                             CONSTRAINT submissions_day_number_check CHECK ((day_number > 0)),
                                             CONSTRAINT chk_submissions_moderation_status CHECK (((moderation_status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'PASSED'::character varying, 'FLAGGED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: submissions_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.submissions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: submissions_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.submissions_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: submissions_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.submissions_id_seq1 OWNED BY challengesphere.submissions.id;


--
-- Name: user_blocks; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.user_blocks (
                                             id bigint NOT NULL,
                                             blocker_user_id bigint NOT NULL,
                                             blocked_user_id bigint NOT NULL,
                                             active boolean DEFAULT true NOT NULL,
                                             created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                             updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                             CONSTRAINT chk_user_blocks_not_self CHECK ((blocker_user_id <> blocked_user_id))
);


--
-- Name: user_blocks_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.user_blocks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_blocks_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

ALTER TABLE challengesphere.user_blocks ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME challengesphere.user_blocks_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
    );


--
-- Name: user_challenges; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.user_challenges (
                                                 id bigint NOT NULL,
                                                 user_id bigint NOT NULL,
                                                 challenge_id bigint NOT NULL,
                                                 joined_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
                                                 completed boolean DEFAULT false,
                                                 completed_at timestamp with time zone,
                                                 active boolean DEFAULT true
);


--
-- Name: user_challenges_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.user_challenges_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_challenges_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.user_challenges_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_challenges_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.user_challenges_id_seq1 OWNED BY challengesphere.user_challenges.id;


--
-- Name: user_preferences; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.user_preferences (
                                                  user_id bigint NOT NULL,
                                                  push_notifications_enabled boolean DEFAULT true NOT NULL,
                                                  email_notifications_enabled boolean DEFAULT true NOT NULL,
                                                  notify_on_glory boolean DEFAULT false NOT NULL,
                                                  notify_on_comment boolean DEFAULT true NOT NULL,
                                                  notify_on_follow boolean DEFAULT true NOT NULL,
                                                  notify_on_challenge_invite boolean DEFAULT true NOT NULL,
                                                  notify_on_challenge_update boolean DEFAULT true NOT NULL,
                                                  private_profile boolean DEFAULT false NOT NULL,
                                                  private_submissions boolean DEFAULT false NOT NULL,
                                                  show_on_leaderboard boolean DEFAULT true NOT NULL,
                                                  allow_challenge_invites boolean DEFAULT true NOT NULL,
                                                  dark_mode boolean DEFAULT false NOT NULL,
                                                  language character varying(8) DEFAULT 'en'::character varying NOT NULL,
                                                  created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                  updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                                  id bigint NOT NULL
);


--
-- Name: user_preferences_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.user_preferences_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_preferences_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.user_preferences_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_preferences_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.user_preferences_id_seq1 OWNED BY challengesphere.user_preferences.id;


--
-- Name: user_reports; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.user_reports (
                                              id bigint NOT NULL,
                                              reporter_user_id bigint NOT NULL,
                                              reported_user_id bigint NOT NULL,
                                              reason character varying(128) NOT NULL,
                                              details text,
                                              status character varying(32) DEFAULT 'OPEN'::character varying NOT NULL,
                                              created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                              CONSTRAINT chk_user_reports_not_self CHECK ((reporter_user_id <> reported_user_id))
);


--
-- Name: user_reports_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.user_reports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_reports_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

ALTER TABLE challengesphere.user_reports ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME challengesphere.user_reports_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
    );


--
-- Name: users; Type: TABLE; Schema: challengesphere; Owner: -
--

CREATE TABLE challengesphere.users (
                                       id bigint NOT NULL,
                                       email_id character varying(255) NOT NULL,
                                       phone character varying(20),
                                       username character varying(50) NOT NULL,
                                       hashed_password character varying(255),
                                       created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
                                       genuineness_score integer DEFAULT 100,
                                       active boolean DEFAULT true,
                                       challenges_created integer DEFAULT 0,
                                       challenges_accepted integer DEFAULT 0,
                                       followers integer DEFAULT 0,
                                       following integer DEFAULT 0,
                                       profile_pic_url text,
                                       premium_user integer DEFAULT 0,
                                       notifications integer DEFAULT 1,
                                       bio text,
                                       google_id character varying(255),
                                       banned_at timestamp with time zone,
                                       ban_reason character varying(512),
                                       last_login_at timestamp with time zone,
                                       name character varying(120),
                                       date_of_birth date,
                                       age integer,
                                       interests text
);


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_id_seq1; Type: SEQUENCE; Schema: challengesphere; Owner: -
--

CREATE SEQUENCE challengesphere.users_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_id_seq1; Type: SEQUENCE OWNED BY; Schema: challengesphere; Owner: -
--

ALTER SEQUENCE challengesphere.users_id_seq1 OWNED BY challengesphere.users.id;


--
-- Name: challenge_invites id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.challenge_invites ALTER COLUMN id SET DEFAULT nextval('challengesphere.challenge_invites_id_seq1'::regclass);


--
-- Name: challenges id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.challenges ALTER COLUMN id SET DEFAULT nextval('challengesphere.challenges_id_seq1'::regclass);


--
-- Name: comments id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.comments ALTER COLUMN id SET DEFAULT nextval('challengesphere.comments_id_seq1'::regclass);


--
-- Name: follows id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.follows ALTER COLUMN id SET DEFAULT nextval('challengesphere.follows_id_seq1'::regclass);


--
-- Name: glories id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.glories ALTER COLUMN id SET DEFAULT nextval('challengesphere.glories_id_seq1'::regclass);


--
-- Name: invite_usages id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.invite_usages ALTER COLUMN id SET DEFAULT nextval('challengesphere.invite_usages_id_seq1'::regclass);


--
-- Name: leaderboard_entries id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.leaderboard_entries ALTER COLUMN id SET DEFAULT nextval('challengesphere.leaderboard_entries_id_seq1'::regclass);


--
-- Name: moderation_flags id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.moderation_flags ALTER COLUMN id SET DEFAULT nextval('challengesphere.moderation_flags_id_seq1'::regclass);


--
-- Name: push_device_tokens id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.push_device_tokens ALTER COLUMN id SET DEFAULT nextval('challengesphere.push_device_tokens_id_seq'::regclass);


--
-- Name: reports id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.reports ALTER COLUMN id SET DEFAULT nextval('challengesphere.reports_id_seq1'::regclass);


--
-- Name: submissions id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.submissions ALTER COLUMN id SET DEFAULT nextval('challengesphere.submissions_id_seq1'::regclass);


--
-- Name: user_challenges id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_challenges ALTER COLUMN id SET DEFAULT nextval('challengesphere.user_challenges_id_seq1'::regclass);


--
-- Name: user_preferences id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_preferences ALTER COLUMN id SET DEFAULT nextval('challengesphere.user_preferences_id_seq1'::regclass);


--
-- Name: users id; Type: DEFAULT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.users ALTER COLUMN id SET DEFAULT nextval('challengesphere.users_id_seq1'::regclass);


--
-- Name: auth_tokens auth_tokens_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.auth_tokens
    ADD CONSTRAINT auth_tokens_pkey PRIMARY KEY (token_hash, token_type);


--
-- Name: challenge_invites challenge_invites_invite_code_key; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.challenge_invites
    ADD CONSTRAINT challenge_invites_invite_code_key UNIQUE (invite_code);


--
-- Name: challenge_invites challenge_invites_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.challenge_invites
    ADD CONSTRAINT challenge_invites_pkey PRIMARY KEY (id);


--
-- Name: challenges challenges_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.challenges
    ADD CONSTRAINT challenges_pkey PRIMARY KEY (id);


--
-- Name: comments comments_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.comments
    ADD CONSTRAINT comments_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: follows follows_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.follows
    ADD CONSTRAINT follows_pkey PRIMARY KEY (id);


--
-- Name: glories glories_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.glories
    ADD CONSTRAINT glories_pkey PRIMARY KEY (id);


--
-- Name: invite_usages invite_usages_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.invite_usages
    ADD CONSTRAINT invite_usages_pkey PRIMARY KEY (id);


--
-- Name: leaderboard_entries leaderboard_entries_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.leaderboard_entries
    ADD CONSTRAINT leaderboard_entries_pkey PRIMARY KEY (id);


--
-- Name: moderation_flags moderation_flags_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.moderation_flags
    ADD CONSTRAINT moderation_flags_pkey PRIMARY KEY (id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: push_device_tokens push_device_tokens_expo_push_token_key; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.push_device_tokens
    ADD CONSTRAINT push_device_tokens_expo_push_token_key UNIQUE (expo_push_token);


--
-- Name: push_device_tokens push_device_tokens_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.push_device_tokens
    ADD CONSTRAINT push_device_tokens_pkey PRIMARY KEY (id);


--
-- Name: reports reports_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.reports
    ADD CONSTRAINT reports_pkey PRIMARY KEY (id);


--
-- Name: submissions submissions_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.submissions
    ADD CONSTRAINT submissions_pkey PRIMARY KEY (id);


--
-- Name: follows uk_follows; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.follows
    ADD CONSTRAINT uk_follows UNIQUE (follower_id, following_id);


--
-- Name: glories uk_glories; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.glories
    ADD CONSTRAINT uk_glories UNIQUE (submission_id, user_id);


--
-- Name: invite_usages uk_invite_usage; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.invite_usages
    ADD CONSTRAINT uk_invite_usage UNIQUE (invite_id, user_id);


--
-- Name: leaderboard_entries uk_leaderboard_challenge_user; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.leaderboard_entries
    ADD CONSTRAINT uk_leaderboard_challenge_user UNIQUE (challenge_id, user_id);


--
-- Name: user_blocks uk_user_blocks_pair; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_blocks
    ADD CONSTRAINT uk_user_blocks_pair UNIQUE (blocker_user_id, blocked_user_id);


--
-- Name: user_challenges uk_user_challenge; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_challenges
    ADD CONSTRAINT uk_user_challenge UNIQUE (user_id, challenge_id);


--
-- Name: user_blocks user_blocks_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_blocks
    ADD CONSTRAINT user_blocks_pkey PRIMARY KEY (id);


--
-- Name: user_challenges user_challenges_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_challenges
    ADD CONSTRAINT user_challenges_pkey PRIMARY KEY (id);


--
-- Name: user_preferences user_preferences_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_preferences
    ADD CONSTRAINT user_preferences_pkey PRIMARY KEY (id);


--
-- Name: user_reports user_reports_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_reports
    ADD CONSTRAINT user_reports_pkey PRIMARY KEY (id);


--
-- Name: users users_email_id_key; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.users
    ADD CONSTRAINT users_email_id_key UNIQUE (email_id);


--
-- Name: users users_google_id_key; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.users
    ADD CONSTRAINT users_google_id_key UNIQUE (google_id);


--
-- Name: users users_phone_key; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.users
    ADD CONSTRAINT users_phone_key UNIQUE (phone);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: idx_auth_tokens_expires_at; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_auth_tokens_expires_at ON challengesphere.auth_tokens USING btree (expires_at);


--
-- Name: idx_auth_tokens_user_id; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_auth_tokens_user_id ON challengesphere.auth_tokens USING btree (user_id);


--
-- Name: idx_challenge_invites_challenge; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_challenge_invites_challenge ON challengesphere.challenge_invites USING btree (challenge_id, active);


--
-- Name: idx_challenge_invites_code; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_challenge_invites_code ON challengesphere.challenge_invites USING btree (invite_code);


--
-- Name: idx_comments_submission_active; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_comments_submission_active ON challengesphere.comments USING btree (submission_id, active, created_at DESC);


--
-- Name: idx_comments_submission_id; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_comments_submission_id ON challengesphere.comments USING btree (submission_id);


--
-- Name: idx_follows_follower_active; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_follows_follower_active ON challengesphere.follows USING btree (follower_id, active);


--
-- Name: idx_follows_following_active; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_follows_following_active ON challengesphere.follows USING btree (following_id, active);


--
-- Name: idx_glories_submission_active; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_glories_submission_active ON challengesphere.glories USING btree (submission_id, active);


--
-- Name: idx_glories_submission_ungloried; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_glories_submission_ungloried ON challengesphere.glories USING btree (submission_id, ungloried) WHERE (ungloried = true);


--
-- Name: idx_invite_usages_invite; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_invite_usages_invite ON challengesphere.invite_usages USING btree (invite_id);


--
-- Name: idx_leaderboard_challenge_score; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_leaderboard_challenge_score ON challengesphere.leaderboard_entries USING btree (challenge_id, score DESC);


--
-- Name: idx_moderation_flags_pending; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_moderation_flags_pending ON challengesphere.moderation_flags USING btree (created_at) WHERE (reviewed_at IS NULL);


--
-- Name: idx_moderation_flags_user; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_moderation_flags_user ON challengesphere.moderation_flags USING btree (user_id);


--
-- Name: idx_notifications_user_unread; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_notifications_user_unread ON challengesphere.notifications USING btree (user_id) WHERE (read_at IS NULL);


--
-- Name: idx_push_device_tokens_enabled_timezone; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_push_device_tokens_enabled_timezone ON challengesphere.push_device_tokens USING btree (enabled, timezone);


--
-- Name: idx_push_device_tokens_user_enabled; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_push_device_tokens_user_enabled ON challengesphere.push_device_tokens USING btree (user_id, enabled);


--
-- Name: idx_reports_submission_id; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_reports_submission_id ON challengesphere.reports USING btree (submission_id);


--
-- Name: idx_submissions_challenge_public; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_submissions_challenge_public ON challengesphere.submissions USING btree (challenge_id, is_private, active);


--
-- Name: idx_submissions_user_challenge_day; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_submissions_user_challenge_day ON challengesphere.submissions USING btree (user_id, challenge_id, day_number);


--
-- Name: idx_submissions_user_time; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_submissions_user_time ON challengesphere.submissions USING btree (user_id, submission_time DESC);

CREATE INDEX idx_submissions_moderation_queue ON challengesphere.submissions USING btree (id) WHERE (((moderation_status)::text = ANY ((ARRAY['PENDING'::character varying, 'FAILED'::character varying, 'PROCESSING'::character varying])::text[])) AND (NULLIF(btrim(media_urls), ''::text) IS NOT NULL));


--
-- Name: idx_user_blocks_blocked_active; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_user_blocks_blocked_active ON challengesphere.user_blocks USING btree (blocked_user_id, active);


--
-- Name: idx_user_blocks_blocker_active; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_user_blocks_blocker_active ON challengesphere.user_blocks USING btree (blocker_user_id, active);


--
-- Name: idx_user_preferences_user_id; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_user_preferences_user_id ON challengesphere.user_preferences USING btree (user_id);


--
-- Name: idx_user_reports_reported_status; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_user_reports_reported_status ON challengesphere.user_reports USING btree (reported_user_id, status, created_at DESC);


--
-- Name: idx_user_reports_reporter_created; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_user_reports_reporter_created ON challengesphere.user_reports USING btree (reporter_user_id, created_at DESC);


--
-- Name: idx_users_interests_active; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_users_interests_active ON challengesphere.users USING btree (active, interests) WHERE ((interests IS NOT NULL) AND (interests <> ''::text));


--
-- Name: idx_users_last_login_at; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE INDEX idx_users_last_login_at ON challengesphere.users USING btree (last_login_at DESC) WHERE (last_login_at IS NOT NULL);


--
-- Name: uk_submission_day_active; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE UNIQUE INDEX uk_submission_day_active ON challengesphere.submissions USING btree (user_id, challenge_id, day_number) WHERE (active = true);


--
-- Name: ux_user_preferences_user_id; Type: INDEX; Schema: challengesphere; Owner: -
--

CREATE UNIQUE INDEX ux_user_preferences_user_id ON challengesphere.user_preferences USING btree (user_id);


--
-- Name: auth_tokens auth_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.auth_tokens
    ADD CONSTRAINT auth_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: challenge_invites challenge_invites_challenge_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.challenge_invites
    ADD CONSTRAINT challenge_invites_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challengesphere.challenges(id) ON DELETE CASCADE;


--
-- Name: challenge_invites challenge_invites_created_by_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.challenge_invites
    ADD CONSTRAINT challenge_invites_created_by_fkey FOREIGN KEY (created_by) REFERENCES challengesphere.users(id);


--
-- Name: challenges challenges_creator_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.challenges
    ADD CONSTRAINT challenges_creator_id_fkey FOREIGN KEY (creator_id) REFERENCES challengesphere.users(id);


--
-- Name: challenges challenges_current_owner_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.challenges
    ADD CONSTRAINT challenges_current_owner_id_fkey FOREIGN KEY (current_owner_id) REFERENCES challengesphere.users(id);


--
-- Name: comments comments_submission_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.comments
    ADD CONSTRAINT comments_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES challengesphere.submissions(id) ON DELETE CASCADE;


--
-- Name: comments comments_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.comments
    ADD CONSTRAINT comments_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: comments fk_comments_submission; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.comments
    ADD CONSTRAINT fk_comments_submission FOREIGN KEY (submission_id) REFERENCES challengesphere.submissions(id) ON DELETE CASCADE;


--
-- Name: reports fk_reports_submission; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.reports
    ADD CONSTRAINT fk_reports_submission FOREIGN KEY (submission_id) REFERENCES challengesphere.submissions(id) ON DELETE CASCADE;


--
-- Name: follows follows_follower_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.follows
    ADD CONSTRAINT follows_follower_id_fkey FOREIGN KEY (follower_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: follows follows_following_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.follows
    ADD CONSTRAINT follows_following_id_fkey FOREIGN KEY (following_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: glories glories_submission_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.glories
    ADD CONSTRAINT glories_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES challengesphere.submissions(id) ON DELETE CASCADE;


--
-- Name: glories glories_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.glories
    ADD CONSTRAINT glories_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: invite_usages invite_usages_invite_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.invite_usages
    ADD CONSTRAINT invite_usages_invite_id_fkey FOREIGN KEY (invite_id) REFERENCES challengesphere.challenge_invites(id) ON DELETE CASCADE;


--
-- Name: invite_usages invite_usages_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.invite_usages
    ADD CONSTRAINT invite_usages_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: leaderboard_entries leaderboard_entries_challenge_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.leaderboard_entries
    ADD CONSTRAINT leaderboard_entries_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challengesphere.challenges(id) ON DELETE CASCADE;


--
-- Name: leaderboard_entries leaderboard_entries_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.leaderboard_entries
    ADD CONSTRAINT leaderboard_entries_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: moderation_flags moderation_flags_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.moderation_flags
    ADD CONSTRAINT moderation_flags_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES challengesphere.users(id) ON DELETE SET NULL;


--
-- Name: moderation_flags moderation_flags_submission_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.moderation_flags
    ADD CONSTRAINT moderation_flags_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES challengesphere.submissions(id) ON DELETE CASCADE;


--
-- Name: moderation_flags moderation_flags_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.moderation_flags
    ADD CONSTRAINT moderation_flags_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: notifications notifications_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.notifications
    ADD CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: push_device_tokens push_device_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.push_device_tokens
    ADD CONSTRAINT push_device_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: reports reports_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.reports
    ADD CONSTRAINT reports_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: submissions submissions_challenge_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.submissions
    ADD CONSTRAINT submissions_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challengesphere.challenges(id) ON DELETE CASCADE;


--
-- Name: submissions submissions_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.submissions
    ADD CONSTRAINT submissions_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: user_blocks user_blocks_blocked_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_blocks
    ADD CONSTRAINT user_blocks_blocked_user_id_fkey FOREIGN KEY (blocked_user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: user_blocks user_blocks_blocker_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_blocks
    ADD CONSTRAINT user_blocks_blocker_user_id_fkey FOREIGN KEY (blocker_user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: user_challenges user_challenges_challenge_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_challenges
    ADD CONSTRAINT user_challenges_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challengesphere.challenges(id) ON DELETE CASCADE;


--
-- Name: user_challenges user_challenges_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_challenges
    ADD CONSTRAINT user_challenges_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: user_preferences user_preferences_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_preferences
    ADD CONSTRAINT user_preferences_user_id_fkey FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: user_reports user_reports_reported_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_reports
    ADD CONSTRAINT user_reports_reported_user_id_fkey FOREIGN KEY (reported_user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- Name: user_reports user_reports_reporter_user_id_fkey; Type: FK CONSTRAINT; Schema: challengesphere; Owner: -
--

ALTER TABLE ONLY challengesphere.user_reports
    ADD CONSTRAINT user_reports_reporter_user_id_fkey FOREIGN KEY (reporter_user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--
