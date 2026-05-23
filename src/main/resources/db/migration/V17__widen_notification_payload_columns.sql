-- Legacy databases may have Hibernate-default varchar(255) columns for
-- notification payloads. Submission notifications can exceed that length.
ALTER TABLE IF EXISTS challengesphere.notifications
    ALTER COLUMN message TYPE TEXT,
    ALTER COLUMN action_object TYPE TEXT,
    ALTER COLUMN "type" TYPE VARCHAR(500);

-- Some early local/prod environments used the default public schema.
ALTER TABLE IF EXISTS public.notifications
    ALTER COLUMN message TYPE TEXT,
    ALTER COLUMN action_object TYPE TEXT,
    ALTER COLUMN "type" TYPE VARCHAR(500);
