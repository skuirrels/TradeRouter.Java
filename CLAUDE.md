# TradeRouter.Java

## Git and GitHub attribution

- Commits, pushes and pull requests are authored only as the GitHub handle `skuirrels`. Do not use any other name.
- Never add `Co-Authored-By` trailers, "Generated with Claude Code" footers, or any other AI attribution to commit messages, pull request titles or descriptions, issue comments or release notes.
- Do not push, open pull requests or create releases unless explicitly asked.

## Build

- Requires JDK 21 or later and Maven 3.9 or later. Build everything with `mvn verify`.
- Modules: `traderouter` (the library and its tests), `traderouter-sample` (console sample), `traderouter-benchmarks` (JMH).
- The library has no runtime dependencies. Do not add one; JSON is read and written by `io.github.skuirrels.traderouter.json`.
- Embedded data lives in `traderouter/src/main/resources/io/github/skuirrels/traderouter/data` and is byte-identical to TradeRouter.Net; its hashes are in `DATA_PROVENANCE.md`.

## Dependencies

- No packages with a commercial licence. Test and benchmark dependencies only: JUnit, AssertJ, Jackson (test JSON validation) and JMH.

## Port fidelity

- This is a port of TradeRouter.Net. Routing results, tie-breaking, GeoJSON shape and the movement text report are expected to match the .NET library exactly; the priority queue in `graph/IntDoubleHeap.java` deliberately mirrors the .NET `PriorityQueue` so equal-cost routes break ties identically.
- Doubles from trigonometry, such as route `length` and sea-area `enter_km`, may differ from .NET in the last digit because the JVM and .NET use different maths libraries. Identifiers, order, point counts and rounded figures must match.
- Keep .NET `internal` types out of the public Java API. Java has no `InternalsVisibleTo`: make the type package-private, expose a small public entry point instead, and test it from a test class in the same package.

## Changes and review

- Never commit to `main` directly. Work on a branch, open a pull request, and merge only after review.
- Before asking to merge, run `/code-review high` on the branch and resolve or explicitly defer every finding. For ports from TradeRouter.Net, also compare outputs with the .NET library and note any differences in the pull request.
- `mvn verify` must pass on the branch before review, and again on the merge result.

## Releases

- A release is only cut from a reviewed, merged `main`. Releasing requires the user's explicit go-ahead, and publishing on the Central Portal cannot be undone.
- Before tagging, set the version in all four POMs, move `project.build.outputTimestamp` to the release date, date the `CHANGELOG.md` entry and update the README Installation section. `docs/releasing.md` has the full checklist.
- Build releases with `mvn -B -P release clean deploy`. Never omit `clean`: a stale `target/` once shipped a deleted data file.
- Before publishing, list `target/central-publishing/central-bundle.zip`, check the jar holds exactly the files under `src/main/resources`, and verify a signature with `gpg --verify`. Show the user the deployment and wait for their confirmation before publishing.
- The GitHub tag workflow cannot sign until the `MAVEN_GPG_KEY`, `MAVEN_GPG_PASSPHRASE` and `CENTRAL_TOKEN_*` secrets exist, so releases currently run from the maintainer's Mac. GPG signing needs the macOS `pinentry-mac` dialog, which the Claude Code sandbox blocks; run the signing and deploy commands with the sandbox disabled.
- The Central Portal account signs in only through "Continue with GitHub" as `skuirrels`; it has no password. The user token in `~/.m2/settings.xml` (server id `central`) can also check, publish or drop a deployment through the Publisher API. Never print it.
