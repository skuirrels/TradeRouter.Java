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
