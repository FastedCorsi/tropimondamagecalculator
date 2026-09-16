# Distribution privacy

By FastedCorsi

## Required checks

Run with Java 21:

```powershell
./gradlew.bat build verifyDistribution
```

On Linux/macOS use `./gradlew`. The normal build runs the same checks automatically.

- `verifyPrivacy`: checks tracked files and untracked files not excluded by Git. A tracked private file fails even when it matches `.gitignore`.
- `verifyModPrivacy`: checks the final remapped mod JAR, including Java constant-pool strings, metadata, resources and nested archives.
- `verifySourcesPrivacy`: checks the remapped sources JAR.
- `sourceDistribution`: creates a ZIP of the checked shareable files, not the whole working directory.
- `verifyDistribution`: checks both JARs and the resulting project ZIP. The remap tasks also run their archive check when called directly.
- `verifyGitIdentity`: verifies the effective author AND committer against the public alias and an existing GitHub noreply address. Run before any separately authorized commit. It never changes Git configuration.

The privacy tool is a separate build source set. It is absent from the runtime JAR and introduces no runtime dependency on another mod.

## Detection and review

The gate checks canonical author metadata, account-specific home paths, email addresses,
private keys, known credential-token patterns, literal secret assignments, credential URLs,
JWT-like values and excluded private entry names. It scans new source files, not just Git diffs.
Findings contain relative file/entry locations and categories, never matched sensitive values.

Local account and effective Git identity terms are read in memory, not saved to a versioned list.
Additional civil names, employer references or other known private terms can be provided in a UTF-8
file outside the repository via the `PRIVACY_TERMS_FILE` environment variable. Set this through the
local shell/CI secret configuration; never put its actual contents or personal location in a shared file.

Images are rejected unless their path and exact SHA-256 were reviewed in
`tools/privacy/reviewed-assets.json`. Current approved artwork is the navigator frame only;
its original rights and attribution remain documented in `third-party-assets.md`.
Changing a pixel or adding an image requires another visual review, including any metadata.

Tests use synthetic data and cover home paths, private terms, metadata, nested archives,
constant-pool strings, tokens, emails, screenshots, sessions and excluded files.
No secret is authenticated, revoked or tested against a service by this tool.

These checks are a publication gate, not a guarantee that every unknown personal name or every
secret format can be recognized. Human review remains mandatory for new credits, data and artwork.
Do not bypass a finding with a broad exclusion. Preserve third-party license/credit information
and functional public URLs; review their provenance if they trigger a check.

## Publishable outputs

- `build/libs/tropimon-damage-calc-VERSION.jar`
- `build/libs/tropimon-damage-calc-VERSION-sources.jar`
- `build/distributions/tropimon-damage-calc-VERSION-project.zip`

Do not distribute `exports`, logs, run profiles, saves, backups, environment files, local service
configuration, screenshots or the raw working directory. The old files remain on disk for their
owner; ignore rules, Git archive exclusions and the source ZIP keep them out of new publication.
Removing a tracked screenshot from the index does not remove it from old commits.

## Historical traces reviewed on 2026-08-31

### Local 0.3.40 validation

- Recompiled with Java 21. `build verifyDistribution verifyGitIdentity` succeeds.
- 179 tests pass, including 15 privacy tests; one optional performance benchmark is skipped.
- The source tree, final JAR, sources JAR and project ZIP pass the privacy gate.
- The canonical author is present; no searched private terms, account paths or credential findings remain in these outputs.
- All 80 runtime class files and functional resource entries are byte-identical to 0.3.39. Only `fabric.mod.json` changes (version and public author).
- Existing calculation, form resolution, cache parity, autonomy and reflective compatibility tests still pass.
- No new Minecraft launch is needed for this metadata-only runtime change; the comparison covers every runtime class and resource.
- The two screenshot originals and old exports remain on disk. The installed 0.3.39 JAR retains its pre-task SHA-256.

### Unchanged older copies

- The 42 commits reachable from HEAD use the verified public Git identity. No Git configuration was changed.
- Commit `7d1af74` introduced the old developer attribution in `src/main/resources/fabric.mod.json`; historical copies still contain it.
- Public release JARs `v0.3.0` through `v0.3.5` were inspected and still contain the old attribution in that metadata file.
- Previous local exports and the installed launcher JAR are not sanitized in place. They remain outside the new distribution.
- Two formerly tracked screenshots are removed only from the current index. Existing public/history copies remain.
- One pre-existing broken internal checkpoint reference prevents a blanket `git log --all` traversal. HEAD history and public releases were inspected separately. No refs were repaired or removed.
- No live secret was identified in the current shareable files during this review. Unknown or inaccessible old copies cannot be certified.

This work does not rewrite history, replace old releases, revoke credentials, push, publish or
install the new JAR in a launcher. Those are separate actions requiring explicit authorization.
