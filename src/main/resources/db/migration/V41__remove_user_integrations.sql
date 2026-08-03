-- Third-party fitness and coding-provider integrations have been removed.
-- Dropping this table permanently deletes previously stored provider credentials
-- and cached provider snapshots.
DROP TABLE IF EXISTS challengesphere.user_integrations;
