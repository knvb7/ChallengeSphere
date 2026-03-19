-- DROP SCHEMA challengesphere;

CREATE SCHEMA challengesphere AUTHORIZATION postgres;

-- DROP SEQUENCE challenge_invites_id_seq;

CREATE SEQUENCE challenge_invites_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE challenges_id_seq;

CREATE SEQUENCE challenges_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE comments_id_seq;

CREATE SEQUENCE comments_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE follows_id_seq;

CREATE SEQUENCE follows_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE glories_id_seq;

CREATE SEQUENCE glories_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE invite_usages_id_seq;

CREATE SEQUENCE invite_usages_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE leaderboard_entries_id_seq;

CREATE SEQUENCE leaderboard_entries_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE likes_id_seq;

CREATE SEQUENCE likes_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE notifications_id_seq;

CREATE SEQUENCE notifications_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE reports_id_seq;

CREATE SEQUENCE reports_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE submissions_id_seq;

CREATE SEQUENCE submissions_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE user_challenges_id_seq;

CREATE SEQUENCE user_challenges_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;
-- DROP SEQUENCE users_id_seq;

CREATE SEQUENCE users_id_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;-- challengesphere."comments" definition

-- Drop table

-- DROP TABLE "comments";

CREATE TABLE "comments" (
	id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
	post_id uuid NOT NULL,
	user_id int8 NOT NULL,
	"content" text NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	active bool DEFAULT true NULL,
	CONSTRAINT comments_pkey PRIMARY KEY (id)
);


-- challengesphere.likes definition

-- Drop table

-- DROP TABLE likes;

CREATE TABLE likes (
	id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
	post_id uuid NOT NULL,
	user_id int8 NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	active bool DEFAULT true NULL,
	CONSTRAINT likes_pkey PRIMARY KEY (id),
	CONSTRAINT likes_post_id_user_id_key UNIQUE (post_id, user_id)
);


-- challengesphere.reports definition

-- Drop table

-- DROP TABLE reports;

CREATE TABLE reports (
	id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
	post_id uuid NOT NULL,
	user_id int8 NOT NULL,
	reason varchar(100) NOT NULL,
	description text NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	status varchar(20) DEFAULT 'PENDING'::character varying NULL,
	active bool DEFAULT true NULL,
	CONSTRAINT reports_pkey PRIMARY KEY (id)
);


-- challengesphere.users definition

-- Drop table

-- DROP TABLE users;

CREATE TABLE users (
	id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
	email_id varchar(255) NOT NULL,
	phone varchar(20) NULL,
	username varchar(50) NOT NULL,
	hashed_password varchar(255) NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	active bool DEFAULT true NULL,
	challenges_created int4 DEFAULT 0 NULL,
	challenges_accepted int4 DEFAULT 0 NULL,
	followers int4 DEFAULT 0 NULL,
	"following" int4 DEFAULT 0 NULL,
	profile_pic_url text NULL,
	premium_user int4 DEFAULT 0 NULL,
	notifications int4 DEFAULT 1 NULL,
	bio text NULL,
	genuineness_score int4 DEFAULT 100 NULL,
	CONSTRAINT users_email_id_key UNIQUE (email_id),
	CONSTRAINT users_phone_key UNIQUE (phone),
	CONSTRAINT users_pkey PRIMARY KEY (id),
	CONSTRAINT users_username_key UNIQUE (username)
);
CREATE INDEX idx_users_username ON challengesphere.users USING btree (username);


-- challengesphere.challenges definition

-- Drop table

-- DROP TABLE challenges;

CREATE TABLE challenges (
	id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
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
	CONSTRAINT challenges_pkey PRIMARY KEY (id),
	CONSTRAINT challenges_creator_id_fkey FOREIGN KEY (creator_id) REFERENCES users(id),
	CONSTRAINT challenges_current_owner_id_fkey FOREIGN KEY (current_owner_id) REFERENCES users(id)
);
CREATE INDEX idx_challenges_creator_id ON challengesphere.challenges USING btree (creator_id);
CREATE INDEX idx_challenges_current_owner_id ON challengesphere.challenges USING btree (current_owner_id);


-- challengesphere.follows definition

-- Drop table

-- DROP TABLE follows;

CREATE TABLE follows (
	id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
	follower_id int8 NOT NULL,
	following_id int8 NOT NULL,
	created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	active bool DEFAULT true NOT NULL,
	CONSTRAINT follows_follower_id_following_id_key UNIQUE (follower_id, following_id),
	CONSTRAINT follows_pkey PRIMARY KEY (id),
	CONSTRAINT follows_follower_id_fkey FOREIGN KEY (follower_id) REFERENCES users(id),
	CONSTRAINT follows_following_id_fkey FOREIGN KEY (following_id) REFERENCES users(id)
);
CREATE INDEX idx_follows_follower_id ON challengesphere.follows USING btree (follower_id);
CREATE INDEX idx_follows_following_id ON challengesphere.follows USING btree (following_id);


-- challengesphere.leaderboard_entries definition

-- Drop table

-- DROP TABLE leaderboard_entries;

CREATE TABLE leaderboard_entries (
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
	CONSTRAINT leaderboard_entries_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challenges(id) ON DELETE CASCADE,
	CONSTRAINT leaderboard_entries_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);


-- challengesphere.notifications definition

-- Drop table

-- DROP TABLE notifications;

CREATE TABLE notifications (
	id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
	user_id int8 NOT NULL,
	"type" varchar(500) NOT NULL,
	message text NOT NULL,
	action_object text NULL,
	sent_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT notifications_pkey PRIMARY KEY (id),
	CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_notifications_type ON challengesphere.notifications USING btree (type);
CREATE INDEX idx_notifications_user_id ON challengesphere.notifications USING btree (user_id);


-- challengesphere.posts definition

-- Drop table

-- DROP TABLE posts;

CREATE TABLE posts (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	user_id int8 NOT NULL,
	challenge_id int8 NOT NULL,
	title varchar(255) NOT NULL,
	"content" text NOT NULL,
	media_url text NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	active bool DEFAULT true NULL,
	likes_count int8 DEFAULT 0 NULL,
	comments_count int8 DEFAULT 0 NULL,
	CONSTRAINT posts_pkey PRIMARY KEY (id),
	CONSTRAINT posts_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challenges(id),
	CONSTRAINT posts_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_posts_created_at ON challengesphere.posts USING btree (created_at);
CREATE INDEX idx_posts_user_id ON challengesphere.posts USING btree (user_id);


-- challengesphere.submissions definition

-- Drop table

-- DROP TABLE submissions;

CREATE TABLE submissions (
	id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL,
	user_id int8 NOT NULL,
	challenge_id int8 NOT NULL,
	day_number int4 NOT NULL,
	description varchar(2000) NOT NULL,
	media_urls text NULL,
	is_private bool DEFAULT false NOT NULL,
	active bool DEFAULT true NOT NULL,
	submission_time timestamp NOT NULL,
	created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamp NULL,
	glories_count int8 DEFAULT 0 NOT NULL,
	comments_count int8 DEFAULT 0 NOT NULL,
	"version" int4 DEFAULT 0 NOT NULL,
	CONSTRAINT check_comments_non_negative CHECK ((comments_count >= 0)),
	CONSTRAINT check_day_number_positive CHECK ((day_number > 0)),
	CONSTRAINT check_glories_non_negative CHECK ((glories_count >= 0)),
	CONSTRAINT submissions_pkey PRIMARY KEY (id),
	CONSTRAINT unique_user_challenge_day UNIQUE (user_id, challenge_id, day_number),
	CONSTRAINT fk_submission_challenge FOREIGN KEY (challenge_id) REFERENCES challenges(id) ON DELETE CASCADE,
	CONSTRAINT fk_submission_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_submissions_active ON challengesphere.submissions USING btree (active) WHERE (active = true);
CREATE INDEX idx_submissions_challenge_id ON challengesphere.submissions USING btree (challenge_id);
CREATE INDEX idx_submissions_challenge_public ON challengesphere.submissions USING btree (challenge_id, is_private, active, created_at DESC);
CREATE INDEX idx_submissions_created_at ON challengesphere.submissions USING btree (created_at DESC);
CREATE INDEX idx_submissions_leaderboard ON challengesphere.submissions USING btree (challenge_id, user_id, active);
CREATE INDEX idx_submissions_public_feed ON challengesphere.submissions USING btree (created_at DESC) WHERE ((active = true) AND (is_private = false));
CREATE INDEX idx_submissions_submission_time ON challengesphere.submissions USING btree (submission_time DESC);
CREATE INDEX idx_submissions_trending ON challengesphere.submissions USING btree (created_at, glories_count DESC, comments_count DESC) WHERE ((active = true) AND (is_private = false));
CREATE INDEX idx_submissions_user_challenge ON challengesphere.submissions USING btree (user_id, challenge_id, day_number);
CREATE INDEX idx_submissions_user_feed ON challengesphere.submissions USING btree (user_id, created_at DESC) WHERE ((active = true) AND (is_private = false));
CREATE INDEX idx_submissions_user_id ON challengesphere.submissions USING btree (user_id);


-- challengesphere.user_challenges definition

-- Drop table

-- DROP TABLE user_challenges;

CREATE TABLE user_challenges (
	id bigserial NOT NULL,
	user_id int8 NOT NULL,
	challenge_id int8 NOT NULL,
	joined_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	completed bool DEFAULT false NULL,
	completed_at timestamptz NULL,
	active bool DEFAULT true NULL,
	CONSTRAINT uk_user_challenge UNIQUE (user_id, challenge_id),
	CONSTRAINT user_challenges_pkey PRIMARY KEY (id),
	CONSTRAINT fk_user_challenges_challenge FOREIGN KEY (challenge_id) REFERENCES challenges(id) ON DELETE CASCADE,
	CONSTRAINT fk_user_challenges_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_user_challenges_active ON challengesphere.user_challenges USING btree (active) WHERE (active = true);
CREATE INDEX idx_user_challenges_challenge_active ON challengesphere.user_challenges USING btree (challenge_id, active);
CREATE INDEX idx_user_challenges_challenge_id ON challengesphere.user_challenges USING btree (challenge_id);
CREATE INDEX idx_user_challenges_completed ON challengesphere.user_challenges USING btree (completed) WHERE (completed = true);
CREATE INDEX idx_user_challenges_user_active ON challengesphere.user_challenges USING btree (user_id, active);
CREATE INDEX idx_user_challenges_user_id ON challengesphere.user_challenges USING btree (user_id);


-- challengesphere.challenge_invites definition

-- Drop table

-- DROP TABLE challenge_invites;

CREATE TABLE challenge_invites (
	id bigserial NOT NULL,
	challenge_id int8 NOT NULL,
	created_by int8 NOT NULL,
	invite_code varchar(12) NOT NULL,
	expires_at timestamptz NULL,
	max_uses int4 NULL,
	use_count int4 DEFAULT 0 NOT NULL,
	active bool DEFAULT true NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT challenge_invites_invite_code_key UNIQUE (invite_code),
	CONSTRAINT challenge_invites_pkey PRIMARY KEY (id),
	CONSTRAINT challenge_invites_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES challenges(id) ON DELETE CASCADE,
	CONSTRAINT challenge_invites_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_challenge_invites_challenge ON challengesphere.challenge_invites USING btree (challenge_id, active);
CREATE INDEX idx_challenge_invites_code ON challengesphere.challenge_invites USING btree (invite_code);


-- challengesphere.glories definition

-- Drop table

-- DROP TABLE glories;

CREATE TABLE glories (
	id bigserial NOT NULL,
	submission_id int8 NOT NULL,
	user_id int8 NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	active bool DEFAULT true NOT NULL,
	CONSTRAINT glories_pkey PRIMARY KEY (id),
	CONSTRAINT uk_glories UNIQUE (submission_id, user_id),
	CONSTRAINT glories_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES submissions(id) ON DELETE CASCADE,
	CONSTRAINT glories_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);


-- challengesphere.invite_usages definition

-- Drop table

-- DROP TABLE invite_usages;

CREATE TABLE invite_usages (
	id bigserial NOT NULL,
	invite_id int8 NOT NULL,
	user_id int8 NOT NULL,
	joined_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT invite_usages_pkey PRIMARY KEY (id),
	CONSTRAINT uk_invite_usage UNIQUE (invite_id, user_id),
	CONSTRAINT invite_usages_invite_id_fkey FOREIGN KEY (invite_id) REFERENCES challenge_invites(id) ON DELETE CASCADE,
	CONSTRAINT invite_usages_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_invite_usages_invite ON challengesphere.invite_usages USING btree (invite_id);