# Feed Avatar Readable URL Design

## Problem

Profile screens show uploaded profile images through a readable URL generated
by `MediaUrlService`, but feed items expose the stored `profilePicUrl` without
that conversion. For private object-storage-backed profile images, the feed avatar source
is therefore not downloadable even though the same user's profile photo loads
elsewhere.

## Design

`FeedServiceImpl.enrichSubmissions` will convert each author's stored profile
photo using `mediaUrlService.toReadableMediaUrl(...)` before it constructs the
`FeedItemResponse`. This reuses the established behavior from
`UserServiceImpl.withReadableProfilePicUrl`.

Feed responses may already exist in Redis with raw avatar URLs. Cached feed
namespace values will be rotated for cached feed types so post-deploy requests
do not receive pre-fix responses.

## Scope

- Modify `FeedServiceImpl` only for avatar response mapping and cached feed
  key versioning.
- Add `FeedServiceImplTest` coverage for readable feed avatar URLs and the new
  explore feed cache namespace.
- No frontend rendering change is required because it already displays the
  `userProfilePicUrl` returned by the API.

## Verification

Run the targeted `FeedServiceImplTest` through Maven, followed by the backend
test suite if targeted verification passes.
