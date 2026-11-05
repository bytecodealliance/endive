# Contributing guide

**Want to contribute? Great!**

We try to make it easy, and all contributions, even the smaller ones, are more than welcome.
This includes bug reports, fixes, documentation, examples...
But first, read this page (including the small print at the end).

* [Coding Philosophy](#coding-philosophy)
* [Before you contribute](#before-you-contribute)
  + [Code reviews](#code-reviews)
  + [Coding Guidelines](#coding-guidelines)
  + [Continuous Integration](#continuous-integration)
  + [Tests and documentation are not optional](#tests-and-documentation-are-not-optional)
  + [Writing docs](#writing-docs)
* [Reporting an issue](#reporting-an-issue)
* [AI Tool Use Policy](#ai-tool-use-policy)
* [Legal](#legal)
* [The small print](#the-small-print)

## Coding Philosophy

Writing a runtime is a big challenge. We want Endive to always be a solid foundation
for running Wasm in Java. In order to accomplish this, it's going to take a large team
of diverse contributors. That's why our goal up front is to aim for writing
simple code that's easy to understand and is as backwards compatible as possible.

The reason is we want to optimize for:

 * attracting more contributors
 * supporting more users
 * supporting more platforms

It's important we focus on this in the beginning phase so that we can grow a large team
of contributors. We also want to make it possible for people with deep Wasm and runtime experience,
but maybe not the deepest Java experience, to contribute.

This philosophy tends to lead us down what might seem like some non-optimal paths. We may ask you
to simplify things, use older versions of Java, or reject improvements that we feel
makes things more confusing without enough measurable benefits.

We don't expect to be able to maintain this forever, and some parts of the codebase will
inevitably suffer from necessary complexity in the name of correctness, safety, or speed.
But we are holding the line as long as we can.

## Before you contribute

To contribute, use GitHub Pull Requests, from your **own** fork.

Also, make sure you have set up your Git authorship correctly:

```
git config --global user.name "Your Full Name"
git config --global user.email your.email@example.com
```

If you use different computers to contribute, please make sure the name is the same on all your computers.

We may use this information to acknowledge your contributions!

### Code reviews

All submissions, including submissions by project members, need to be reviewed and approved by at least one project owner before being merged.

[GitHub Pull Request Review Process](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/reviewing-changes-in-pull-requests/about-pull-request-reviews) is followed for every pull request.

### Coding Guidelines

 * We primarily use the Git history to track authorship. GitHub also has [this nice page with your contributions](https://github.com/quarkusio/quarkus/graphs/contributors).
 * Please take care to write code that fits with existing code styles. The syntactic formatting is automated and can be applied project wise using the command `mvn spotless:apply`.
 * Commits should be atomic and semantic. Please properly squash your pull requests before submitting them. Fixup commits can be used temporarily during the review process but things should be squashed at the end to have meaningful commits.
 * We typically squash and merge pull requests when they are approved. This tends to keep the commit history a little bit more tidy without placing undue burden on the developers.

### Building the Runtime

Contributors and other advanced users may want to build the runtime from source. To do so, you'll need to use Java and Maven:
* `Java version 11+` is required for a proper build. You can download and install [Java 11 Temurin](https://adoptium.net/temurin/releases/?version=11)
* You need Maven. If you don't have Maven installed, replace `mvn` in the below commands with `./mvnw` (Linux/Mac) or `./mvnw.cmd` (Windows).

Basic steps:

* `mvn clean install` to run all of the project's tests and install the library in your local repo
* `mvn -Dquickly` to install the library skipping all tests
* `mvn -Ddev <...goals>` to disable linters and enforcers during development
* `mvn spotless:apply` to autoformat the code
* `./scripts/compile-resources.sh` will recompile and regenerate the `resources/compiled` folders

note: if you're working using a *corporate proxy* (or anything like this), you might need to pass the usual `-Dhttps.proxyHost=...` and `-Dhttps.proxyPort=...` in order to properly instruct Maven about this (this can be required for example for `test-gen-plugin` since it downloads the testsuite).

### Redline and the Cranelift bridge

The experimental redline native compiler needs `cranelift_bridge.wasm`, a Rust crate compiled to `wasm32-wasip1`. **You do not need a Rust toolchain to build Endive.** The [inlay](https://github.com/roastedroot/inlay) Maven plugin downloads a prebuilt copy from GHCR during `generate-sources`, pinned by digest in `redline/wkg.lock`, so a fresh clone builds with a plain `mvn clean install`.

To work on the Rust side you do need Rust with the `wasm32-wasip1` target:

* `make -C redline/wasm-build all` builds `redline/cranelift_bridge.wasm` (gitignored)
* inlay skips the download whenever that file already exists, so your local build picks it up

That skip has a sharp edge: a **stale** `redline/cranelift_bridge.wasm` left over from an earlier `make all` silently shadows the pinned artifact, and you end up testing against a different bridge than CI. Delete the file to go back to the published one.

Publishing is handled by `.github/workflows/wasm-publish.yaml`. Pushes to `main` touching `redline/wasm-build/**` refresh the development snapshot; a manual dispatch can publish any semver tag. The tag must be valid semver — the `wkg.lock` format rejects `latest` — and the workflow validates that before pushing anything.

Which tag the build consumes is the `cranelift-bridge.version` property in the root `pom.xml`. Publishing does **not** update it, and does **not** refresh the lock file: until both are updated the build keeps resolving the previously pinned digest, and re-pushing an already-locked tag makes every build fail with a digest mismatch rather than silently drifting.

To adopt a published wasm (the workflow prints these in its job summary):

```bash
# 1. point the build at the tag that was published
./mvnw versions:set-property -Dproperty=cranelift-bridge.version \
    -DnewVersion=<version> -DgenerateBackupPoms=false

# 2. re-pin the digest
./mvnw generate-sources -pl :redline-bridge-experimental -Dinlay.update

# 3. commit pom.xml and redline/wkg.lock together
```

**Releases handle this automatically.** `release.yaml` retags the digest currently pinned in `wkg.lock` as the release version, points the property at it, re-pins the lock, and commits both alongside the version bump — so every release has a matching immutable wasm artifact, byte-identical to the one CI tested. It retags rather than rebuilding, so the release needs no Rust toolchain. Afterwards it restores the snapshot property, and a guard refuses to deploy if the property still resolves to a `SNAPSHOT` tag.

### Proposals implementation

Our priority is to focus on implementing [proposals](https://github.com/WebAssembly/proposals) that are in the most advanced stages of development. While we wholeheartedly encourage and support explorations, we’ll be dedicating less time to early-stage proposals until we have more comprehensive support for those that are stabilized.

### Continuous Integration

Because we are all humans, and to ensure Endive evolves in the right direction, all changes must pass continuous integration before being merged. The CI is based on GitHub Actions, which means that pull requests will receive automatic feedback.  Please watch out for the results of these workflows to see if your PR passes all tests.

### OWASP Dependency Check

A nightly [OWASP Dependency Check](https://dependency-check.github.io/DependencyCheck/) runs against all modules and fails the build if any dependency has a CVSS score of 7 or higher. Only published (BOM) modules are scanned; internal and test-only modules opt out by setting:

```xml
<properties>
  <dependency-check.skip>true</dependency-check.skip>
</properties>
```

If you add a new module that is **not** published in the BOM, add the property above to its `pom.xml`. New modules without this property will be scanned by default.

### IntelliJ default limits

Some of the SIMD tests are exceeding the default limits of IntelliJ.
To overcome this issue go to "Help menu" -> "Edit Custom Properties" and add the following line:

```
idea.max.intellisense.filesize=5000
```

### Wildcard imports

In this project, we disallow wildcard imports, when using IntelliJ we suggest to apply [this configuration](https://www.jetbrains.com/help/idea/creating-and-optimizing-imports.html#disable-wildcard-imports).

### Tests and documentation are not optional

Don't forget to include tests in your pull requests.
Also don't forget the documentation (reference documentation, javadoc...).

To automatically apply and approve e new version of the "Golden samples" used by the Approval tests you can use the environment variable:
```
APPROVAL_TESTS_USE_REPORTER=AutoApproveReporter
```

### Writing docs

The website lives in `docs/` (see [its README](docs/README.md) for the build). Every page under `docs/docs/` follows these rules:

- Describe how to use a feature, not how it is implemented. Internals belong in Javadoc or in the pull request description.
- No release-time or migration statements. Those go in a blog post; breaking changes relative to Chicory go in the migration guide.
- One code sample per option. Show a builder once per page.
- Body headings start at `##`; the frontmatter `title` is the page heading. Open every page with one plain sentence saying what it covers, which becomes its description in `llms.txt`.
- Names: Wasm, interpreter, runtime compiler, build-time compiler. Version placeholder: `${endive.version}`.
- Every page is executed by `npm test` in `docs/`: keep the hidden `//DEPS` and `docs.FileOps.writeResult(...)` blocks, and update `docs/tests/approvals/` when a page moves.

## Reporting an issue

This project uses GitHub issues to manage the issues. Open an issue directly in GitHub.

If you believe you found a bug, and it's likely possible, please indicate a way to reproduce it, what you are seeing and what you would expect to see.

## AI Tool Use Policy

This project follows the [Bytecode Alliance AI Tool Use Policy](https://github.com/bytecodealliance/governance/blob/main/AI_TOOL_POLICY.md).

## Legal

All original contributions to Endive projects are licensed under the
[ASL - Apache License](https://www.apache.org/licenses/LICENSE-2.0),
version 2.0 or later, or, if another license is specified as governing the file or directory being
modified, such other license.

## The small print

This project is an open source project. Please act responsibly, be nice, polite and enjoy!
