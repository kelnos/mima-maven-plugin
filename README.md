# mima-maven-plugin

This is a Maven plugin that allows you to check your Scala project
against a previous version of the project for binary incompatibilities
that you may have introduced.  This plugin is just a shell; the heavy
lifting is done by the core of
[MiMa](https://github.com/lightbend/mima).

Releases here will match the version of the corresponding MiMa release.
If extra releases of this plugin are required for a given MiMa version,
a fourth version component will be added to the plugin's version number.

## Usage

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.spurint.maven.plugins</groupId>
      <artifactId>mima-maven-plugin</artifactId>
      <version>${mima-maven-plugin.version}</version>
      <executions>
        <execution>
          <id>check-abi</id>
          <goals>
            <goal>check-abi</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

In general, the out-of-the-box configuration should have sane defaults
and will do what you want.  The maven plugin differs a bit in
configuration from the sbt plugin, since we can't write arbitrary code
to provide as configuration as we could with sbt.

This plugin, by default, will figure out the previous version to use for
comparison by fetching the `maven-metadata.xml` file for the artifact
being built from whatever remote repositories are defined in your POM
(which is just maven central if you haven't defined any).  It finds the
latest release version from that metadata file, and then attempts to
fetch that version of the artifact from the remote repository.

For allowing/disallowing binary incompatibilities, the plugin's default
configuration follows the rules set out in [Semantic
Versioning](https://semver.org/), namely:

1. If the version's major number is `0` (as in, `0.4.21`), binary
   incompatibilities are only allowed if the minor version has been
   bumped (as in, `4` -> `5`).
2. Otherwise (as in, `2.0.4`), binary incompatibilities are only allowed
   if the major version has been bumped (as in, `2` -> `3`).

## Configuration

| Name | Default | Description |
|:-----|:--------|:------------|
` previousArtifact` | `latest-release` | If set to `latest-release`, will attempt to determine the most recent released version as described above; otherwise, this can be set to a specific version of an artifact. |
| `failOnProblem` | `semver` | If set to `semver`, the previous artifact version is compared with the current version, and whether or not incompatibilities will cause the build to fail is decided based on Semantic Versioning rules, as described above.  If set to `never` or `false`, binary incompatibilities are never allowed, while `always` or `true` will put the plugin into warn-only mode. |
| `failOnNoPrevious` | `false` | Whether or not to fail the build if a previous artifact version is not supplied or cannot be found. |
| `readTimeout` | `4000` | Timeout in ms to fetch the previous artifact. |
| `direction` | `backward` | Whether to do `backward` compatibilities checks, `forward` compatibility checks, or `both`. |
| `filters` | (none) | A list of `<filter>` elements that allow you to ignore particular problems (see below). |

### Filters

Filters allow you to ignore particular problems (perhaps for changes in
internal non-public classes that nevertheless need to be public).  To
specify a filter, you can add something like:

```xml
<configuration>
  ...
  <filters>
    <filter>
      <name>DirectMissingMethodProblem</name>
      <value>com.example.Foo.create</value>
    </filter>
  </filters>
  ...
</configuration>
```

The `name` property comes from one of the `ProblemRef` subclasses, which
you can discover by taking a look at the [MiMa core
source](https://github.com/lightbend/mima/blob/master/core/src/main/scala/com/typesafe/tools/mima/core/Problems.scala).

When a binary issue is reported as an error, it will also print out a
"filter with:" line that shows the exact filter definition to use to
suppress the error.
