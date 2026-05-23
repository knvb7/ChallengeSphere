-- Legacy production databases may still have varchar(255) for submission text
-- fields from an earlier schema. Multi-photo submissions exceed that limit.
ALTER TABLE IF EXISTS challengesphere.submissions
    ALTER COLUMN media_urls TYPE TEXT,
    ALTER COLUMN description TYPE TEXT;

-- Some early local/prod environments used the default public schema.
ALTER TABLE IF EXISTS public.submissions
    ALTER COLUMN media_urls TYPE TEXT,
    ALTER COLUMN description TYPE TEXT;
