# Using ballerinax/github

## Release asset uploads go to uploads.github.com
- `github:Client` defaults to `serviceUrl = "https://api.github.com"`. GitHub serves release asset uploads ONLY on `https://uploads.github.com`; calling `.../releases/[releaseId]/assets.post(...)` on the default client fails.
- Create a second client for uploads and use it for the asset upload only. Every other call stays on the default client.
```ballerina
final github:Client githubClient = check new ({auth: {token: githubToken}});
final github:Client githubUploadsClient = check new ({auth: {token: githubToken}}, serviceUrl = "https://uploads.github.com");

github:ReleaseAsset uploadedAsset = check githubUploadsClient->/repos/[owner]/[repo]/releases/[releaseId]/assets.post(
    artifactBytes, name = artifactFileName);
```
- The payload is the raw file bytes (`byte[]`), not JSON. The connector sends `Content-Type: application/octet-stream`, which GitHub accepts for any asset; pass a specific media type through `headers` only when the user asks for one.

## Listing APIs are paginated
- List resources (`.../commits`, `.../pulls`, `.../issues`, `.../releases`, ...) accept `perPage` (default 30, max 100) and `page` (default 1) through their `*Queries` record. The connector always sends the defaults, so a single call returns at most 30 items with no error.
- When the requirement needs ALL items, loop: request `perPage = 100`, increment `page` until a response comes back with fewer than 100 items.
- `.../compare/[basehead]` returns at most 250 commits; for larger ranges list commits with `sha`/`since`/`until` and paginate.
