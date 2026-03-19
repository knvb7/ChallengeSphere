-- Challenge Invite Links
CREATE TABLE IF NOT EXISTS challengesphere.challenge_invites (
    id BIGSERIAL PRIMARY KEY,
    challenge_id BIGINT NOT NULL REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    invite_code VARCHAR(12) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE,
    max_uses INT,
    use_count INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Track each invite redemption
CREATE TABLE IF NOT EXISTS challengesphere.invite_usages (
    id BIGSERIAL PRIMARY KEY,
    invite_id BIGINT NOT NULL REFERENCES challengesphere.challenge_invites(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_invite_usage UNIQUE (invite_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_challenge_invites_code ON challengesphere.challenge_invites(invite_code);
CREATE INDEX IF NOT EXISTS idx_challenge_invites_challenge ON challengesphere.challenge_invites(challenge_id, active);
CREATE INDEX IF NOT EXISTS idx_invite_usages_invite ON challengesphere.invite_usages(invite_id);

