# Feed Avatar Readable URL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return downloadable profile picture URLs on feed items and prevent stale cached feed responses from preserving broken avatar values.

**Architecture:** `FeedServiceImpl` already enriches submissions with user data and signs submitted media using `MediaUrlService`. The implementation will use the same service for author profile photos and rotate the cached feed namespace used for enriched responses.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, Maven

---

### Task 1: Reproduce The Feed Avatar Defect

**Files:**
- Modify: `src/test/java/com/ironside/challengesphere/service/impl/FeedServiceImplTest.java`

- [ ] **Step 1: Add a test with a stored profile photo URL**

Build an active explore-feed post whose author stores an object-storage profile image,
configure `mediaUrlService.toReadableMediaUrl(storedUrl)` to return a readable
URL, and assert that the feed item exposes the readable URL.

- [ ] **Step 2: Run the targeted unit test**

Run: `./mvnw test -Dtest=FeedServiceImplTest`

Expected: the readable-avatar assertion fails before the implementation.

### Task 2: Repair Feed Enrichment And Stale Cache Reads

**Files:**
- Modify: `src/main/java/com/ironside/challengesphere/service/impl/FeedServiceImpl.java`
- Modify: `src/test/java/com/ironside/challengesphere/service/impl/FeedServiceImplTest.java`

- [ ] **Step 1: Normalize avatar URL during feed construction**

Pass `mediaUrlService.toReadableMediaUrl(user.getProfilePicUrl())` as the
`userProfilePicUrl` argument to `FeedItemResponse.fromSubmission(...)`.

- [ ] **Step 2: Rotate response cache namespaces**

Define cache key constants for enriched feed payloads with a new readable
avatar version suffix and use them consistently for cache reads and writes.
Update the explore regression setup to expect the new cache key.

- [ ] **Step 3: Run the focused unit test**

Run: `./mvnw test -Dtest=FeedServiceImplTest`

Expected: the feed service tests pass.

### Task 3: Verify Backend Safety

**Files:**
- Modify: none

- [ ] **Step 1: Run backend tests**

Run: `./mvnw test`

Expected: exit code `0` with no test failures.

- [ ] **Step 2: Check patch quality**

Run: `git diff --check`

Expected: exit code `0` with no whitespace errors.
