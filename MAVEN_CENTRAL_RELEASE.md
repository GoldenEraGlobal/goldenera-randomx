# Maven Central release: goldenera-randomx 0.0.1

Coordinates:

```xml
<dependency>
    <groupId>global.goldenera.randomx</groupId>
    <artifactId>goldenera-randomx</artifactId>
    <version>0.0.1</version>
</dependency>
```

Expected Maven Central URL after publication:

https://repo1.maven.org/maven2/global/goldenera/randomx/goldenera-randomx/0.0.1/

## Required credentials

Do not commit credentials. Configure them in the release runner only:

- `CENTRAL_USERNAME`: Sonatype Central Portal token username.
- `CENTRAL_PASSWORD`: Sonatype Central Portal token password.
- `MAVEN_GPG_PRIVATE_KEY`: ASCII-armored GPG private key used to sign artifacts.
- `MAVEN_GPG_PASSPHRASE`: passphrase for the signing key.

`~/.m2/settings.xml` must provide a server matching the POM's `publishingServerId`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>${env.CENTRAL_USERNAME}</username>
      <password>${env.CENTRAL_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

Import the signing key before release:

```bash
printf '%s' "$MAVEN_GPG_PRIVATE_KEY" | gpg --batch --import
```

## Manual release command

From a clean checkout of the merged release commit:

```bash
./mvnw -Pcentral-release -DskipTests deploy \
  -Dgpg.passphrase="$MAVEN_GPG_PASSPHRASE"
```

The publication should stay staged in Central Portal because `autoPublish` is false. Review the deployment in the Central Portal, then publish it manually. Consumers should not need any `<repositories>` entries or credentials once Central sync is complete.

## Local verification

```bash
./mvnw -DskipTests package
```

This repository keeps the approved coordinates unchanged: `global.goldenera.randomx:goldenera-randomx:0.0.1`.
