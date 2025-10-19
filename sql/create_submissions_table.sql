-- Submissions table for ChallengeSphere
CREATE TABLE challengesphere.submissions (
    id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id BIGINT NOT NULL,
    challenge_id BIGINT NOT NULL,
    day_number INT NOT NULL,
    description TEXT NOT NULL,
    media_urls TEXT,
    is_private BOOLEAN NOT NULL DEFAULT FALSE,
    submission_date DATE NOT NULL DEFAULT CURRENT_DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    likes_count BIGINT NOT NULL DEFAULT 0,
    comments_count BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    FOREIGN KEY (challenge_id) REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
    UNIQUE(user_id, challenge_id, day_number)
);

-- Indexes for better query performance
CREATE INDEX idx_submissions_user_id ON challengesphere.submissions(user_id);
CREATE INDEX idx_submissions_challenge_id ON challengesphere.submissions(challenge_id);
CREATE INDEX idx_submissions_created_at ON challengesphere.submissions(created_at DESC);
CREATE INDEX idx_submissions_active ON challengesphere.submissions(active);
CREATE INDEX idx_submissions_is_private ON challengesphere.submissions(is_private);

-- Index for finding public submissions of a challenge
CREATE INDEX idx_submissions_challenge_public ON challengesphere.submissions(challenge_id, is_private, active);

-- Comment on table
COMMENT ON TABLE challengesphere.submissions IS 'Stores daily submissions by users for challenges they are participating in';

