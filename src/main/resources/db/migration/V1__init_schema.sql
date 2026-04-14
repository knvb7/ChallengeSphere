CREATE SCHEMA IF NOT EXISTS challengesphere;

CREATE TABLE IF NOT EXISTS challengesphere.users (
    id BIGSERIAL PRIMARY KEY,
    email_id VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(20) UNIQUE,
    username VARCHAR(50) NOT NULL UNIQUE,
    hashed_password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    genuineness_score INT DEFAULT 100,
    active BOOLEAN DEFAULT TRUE,
    challenges_created INT DEFAULT 0,
    challenges_accepted INT DEFAULT 0,
    followers INT DEFAULT 0,
    following INT DEFAULT 0,
    profile_pic_url TEXT,
    premium_user INT DEFAULT 0,
    notifications INT DEFAULT 1,
    bio TEXT
);

CREATE TABLE IF NOT EXISTS challengesphere.challenges (
    id BIGSERIAL PRIMARY KEY,
    hashtags TEXT,
    categories TEXT,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    benefits TEXT,
    private BOOLEAN DEFAULT FALSE,
    start_date DATE NOT NULL,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    creator_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    current_owner_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    number_of_days INT NOT NULL
);

CREATE TABLE IF NOT EXISTS challengesphere.user_challenges (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    challenge_id BIGINT NOT NULL REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
    joined_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    completed BOOLEAN DEFAULT FALSE,
    completed_at TIMESTAMP WITH TIME ZONE,
    active BOOLEAN DEFAULT TRUE,
    CONSTRAINT uk_user_challenge UNIQUE (user_id, challenge_id)
);

CREATE TABLE IF NOT EXISTS challengesphere.submissions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    challenge_id BIGINT NOT NULL REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
    day_number INT NOT NULL CHECK (day_number > 0),
    description VARCHAR(2000) NOT NULL,
    media_urls TEXT,
    is_private BOOLEAN NOT NULL DEFAULT FALSE,
    submission_time TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    glories_count BIGINT NOT NULL DEFAULT 0,
    comments_count BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_submission_day UNIQUE (user_id, challenge_id, day_number)
);

CREATE TABLE IF NOT EXISTS challengesphere.follows (
    id BIGSERIAL PRIMARY KEY,
    follower_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    following_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_follows UNIQUE (follower_id, following_id)
);

CREATE TABLE IF NOT EXISTS challengesphere.comments (
    id BIGSERIAL PRIMARY KEY,
    submission_id BIGINT REFERENCES challengesphere.submissions(id) ON DELETE CASCADE,
    post_id UUID,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS challengesphere.glories (
    id BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL REFERENCES challengesphere.submissions(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_glories UNIQUE (submission_id, user_id)
);

CREATE TABLE IF NOT EXISTS challengesphere.notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    type VARCHAR(500) NOT NULL,
    message TEXT NOT NULL,
    action_object TEXT,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS challengesphere.reports (
    id BIGSERIAL PRIMARY KEY,
    post_id UUID NOT NULL,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    reason VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING',
    active BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS challengesphere.leaderboard_entries (
    id BIGSERIAL PRIMARY KEY,
    challenge_id BIGINT NOT NULL REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    score DOUBLE PRECISION NOT NULL DEFAULT 0,
    current_rank INT,
    previous_rank INT,
    submission_count INT DEFAULT 0,
    glory_points INT DEFAULT 0,
    streak_days INT DEFAULT 0,
    best_score DOUBLE PRECISION DEFAULT 0,
    last_submission_at TIMESTAMP,
    first_submission_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_leaderboard_challenge_user UNIQUE (challenge_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_submissions_challenge_public
ON challengesphere.submissions(challenge_id, is_private, active);

CREATE INDEX IF NOT EXISTS idx_submissions_user_time
ON challengesphere.submissions(user_id, submission_time DESC);

CREATE INDEX IF NOT EXISTS idx_follows_follower_active
ON challengesphere.follows(follower_id, active);

CREATE INDEX IF NOT EXISTS idx_follows_following_active
ON challengesphere.follows(following_id, active);

CREATE INDEX IF NOT EXISTS idx_comments_submission_active
ON challengesphere.comments(submission_id, active, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_glories_submission_active
ON challengesphere.glories(submission_id, active);

CREATE INDEX IF NOT EXISTS idx_leaderboard_challenge_score
ON challengesphere.leaderboard_entries(challenge_id, score DESC);
