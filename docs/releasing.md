# Releasing to Maven Central

Only the parent POM `io.github.skuirrels:traderouter-parent` and the library `io.github.skuirrels:traderouter` are published. The sample and benchmarks modules are excluded by the `release` profile in `pom.xml`. Each release uploads the library jar, its sources and Javadoc jars and the POMs, all signed. The library jar also carries `LICENSE`, `THIRD-PARTY-NOTICES.md` and `DATA_PROVENANCE.md` under `META-INF`.

Publishing goes through the [Central Portal](https://central.sonatype.com) with `central-publishing-maven-plugin`. A deployment is uploaded and validated but not published: it waits on the portal's Deployments page until it is published by hand. Once published, a version cannot be changed or removed.

## One-time setup

1. Sign in to the Central Portal with the `skuirrels` GitHub account and confirm that the `io.github.skuirrels` namespace shows as verified.
2. Generate a user token on the portal under **View Account > Generate User Token**.
3. Create a GPG key for signing and publish its public key to a key server Central reads, such as `keys.openpgp.org`:

   ```bash
   gpg --full-generate-key
   ```

   ```bash
   gpg --keyserver keys.openpgp.org --send-keys <key id>
   ```

4. For local releases, add the token to `~/.m2/settings.xml` under the server id `central`:

   ```xml
   <servers>
     <server>
       <id>central</id>
       <username>token username</username>
       <password>token password</password>
     </server>
   </servers>
   ```

5. For releases from GitHub Actions, add these secrets to the repository or to its `maven-central` environment:

   | Secret | Value |
   |---|---|
   | `CENTRAL_TOKEN_USERNAME` | Central Portal token username |
   | `CENTRAL_TOKEN_PASSWORD` | Central Portal token password |
   | `MAVEN_GPG_KEY` | ASCII-armoured private key, from `gpg --armor --export-secret-keys <key id>` |
   | `MAVEN_GPG_PASSPHRASE` | Passphrase of that key |

## Testing the signing setup

Running the Release workflow by hand is a dry run. It builds, tests and signs with the `maven-central` secrets, checks each signature against the key in `MAVEN_GPG_KEY` and that the key is on `keys.openpgp.org`, checks that the Central Portal accepts the token in `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD`, and uploads nothing. The Publisher API accepts a valid token for any namespace, so the token check cannot show whether the token may publish to `io.github.skuirrels`:

```bash
gh workflow run release.yml --ref main
```

## Each release

1. Set the version in all four POMs, without `-SNAPSHOT`, and move `project.build.outputTimestamp` in `pom.xml` forward to the release date.
2. Move the `[Unreleased]` entries in `CHANGELOG.md` under the new version and date, and update the version in the README's Installation section.
3. Check the build:

   ```bash
   mvn -B -P release -Dgpg.skip -DskipPublishing=true deploy
   ```

4. Commit, then tag and push the tag. `.github/workflows/release.yml` checks that the tag matches the project version, runs the tests, signs and uploads:

   ```bash
   git tag v2.0.0
   ```

   ```bash
   git push origin v2.0.0
   ```

   To release from this machine instead, run the same deploy with signing through the local gpg agent:

   ```bash
   mvn -B -P release deploy
   ```

5. On the portal's Deployments page, check the validated deployment's file list, then select **Publish**. The artefacts reach Maven Central search within about 30 minutes.
