
# Endive Fork Plan — Chicory to Bytecode Alliance

This project is being forked under the Bytecode Alliance.
We need to take care of all the pre-requisites and legal aspects.

## Decisions

- **ArtifactId naming**: Keep current names (`runtime`, `compiler`, `wasm`, etc.) under `run.endive` groupId
- **License**: Keep Apache 2.0 as-is — exemption already granted by BA. Do NOT modify the LICENSE file.
- **Copyright headers**: No — do not add copyright headers to source files
- **Blog posts**: Keep as historical data, move to a separate section in docs
- **Transparent logos**: Create from existing PNGs

---

## 1. Namespace Rename (`com.dylibso.chicory` -> `run.endive`)

Bulk rename across the entire repo:

- **Java packages**: All `package com.dylibso.chicory.*` and `import` statements (~236 source + ~61 test files)
- **Directory structure**: `src/main/java/com/dylibso/chicory/` -> `src/main/java/run/endive/`
- **module-info.java**: Module names `com.dylibso.chicory.*` -> `run.endive.*`
- **pom.xml files** (~28): `<groupId>`, internal dependency references
- **Root pom.xml**: `<name>`, `<url>`, `<organization>`, `<developers>`, `<scm>`, `<issueManagement>`
- **Android tests**: `gradle/libs.versions.toml` references `com.dylibso.chicory:*`
- **Java templates**: `wasm/src/main/java-templates/com/dylibso/chicory/wasm/Version.java`

Validate with `mvn compile` after rename.

---

## 2. Legal & Attribution

### 2.1 NOTICE file
Create `NOTICE` in repo root:
```
Endive
Copyright 2026 The Bytecode Alliance

This project is based on Chicory, originally created by Dylibso, Inc.
Original source: https://github.com/dylibso/chicory
```

### 2.2 LICENSE
Do NOT update. Already Apache 2.0, exemption granted for the LLVM exception requirement.

---

## 3. README Rewrite

Following the Pekko model and BA README requirements:

- **Header**: Project name "Endive" + new logo + one-line description
- **BA branding** (required): `<strong>A <a href="https://bytecodealliance.org/">Bytecode Alliance</a> hosted project</strong>`
- **Fork attribution** (Pekko-style): "Endive is a fork of [Chicory](https://github.com/dylibso/chicory) by Dylibso, Inc. We thank Dylibso for the incubation period."
- **Goals**: Position as "the default Wasm runtime on the JVM" — remove hedging/"negative" references around speed
- **Update all links**: chicory.dev -> endive.run, dylibso/chicory -> bytecodealliance/endive

---

## 4. Governance Files

### 4.1 CODE_OF_CONDUCT.md (REQUIRED, missing)
Create `CODE_OF_CONDUCT.md` linking to [BA Code of Conduct](https://github.com/bytecodealliance/governance/blob/main/CODE_OF_CONDUCT.md).

### 4.2 CODEOWNERS (recommended)
Add `.github/CODEOWNERS` assigning reviewers.

### 4.3 SECURITY.md (needs update)
Currently references "Chicory team" and `dylibso/chicory` security advisories. Update to reference Endive/BA.

### 4.4 CONTRIBUTING.md (needs update)
Still references Chicory — update project name, links, philosophy section.

### 4.5 ADOPTERS.md (recommended)
Extract adopter list from README into dedicated `ADOPTERS.md`.

### 4.6 Automated license validation in CI (required)
Add `license-maven-plugin` or equivalent. Validate all deps use BA-allowed licenses.

### 4.7 Dependency auditing in CI (required)
Add OWASP Dependency-Check or similar.

---

## 5. CI/CD & Build Configuration

### 5.1 GitHub Workflows
- **release.yaml**: Bot identity `"Chicory BOT" <chicory@dylibso.com>` -> update for Endive
- **release.yaml**: Publishing credentials — new Maven Central credentials under BA org
- **perf.yaml**: References `nightly.link/dylibso/chicory` -> update
- **All workflows**: Review and update any hardcoded chicory/dylibso references

### 5.2 Maven Central Publishing
- New Sonatype/Central credentials for `run.endive` groupId
- New GPG signing key for BA org
- Update `central-publishing-maven-plugin` config

### 5.3 Root pom.xml Metadata
- `<organization>` -> Bytecode Alliance
- `<developers>` -> Update email from `oss@dylibso.com`
- `<scm>` -> `github.com/bytecodealliance/endive`
- `<issueManagement>` -> `github.com/bytecodealliance/endive/issues`
- `<url>` -> `https://endive.run`

---

## 6. Branding & Logos

Source: `/home/andreatp/Documents/Endive_logos/` (8 PNG variants)

- Copy appropriate variants into repo root and `docs/static/img/`
- Create transparent-background versions from existing PNGs
- Replace `chicory1.png` in repo root and docs
- Update `docs/static/img/favicon.ico`

---

## 7. Other Files

- **AGENT.md**: Completely references Chicory — update to Endive
- **scripts/build-jmh-main.sh**: Clones from `github.com/dylibso/chicory` — update URL
- **docs/docusaurus.config.ts**: title, tagline, url (-> endive.run), organizationName, projectName, copyright
- **Blog posts**: Keep as historical data, move to separate section (handle with docs follow-up)

---

## 8. Deferred Items

- Documentation site deployment/hosting
- Full docs content rewrite
- Blog posts reorganization

---

## Execution Order

1. **Scripted bulk rename** (namespace, directories, pom files)
2. **Build validation** — `mvn compile`
3. **Legal files** — NOTICE, CODE_OF_CONDUCT.md
4. **README rewrite** — BA header, attribution, goals
5. **Governance files** — SECURITY.md, CONTRIBUTING.md, CODEOWNERS, ADOPTERS.md
6. **Root pom.xml metadata** — organization, SCM, developers
7. **CI/CD updates** — workflows, bot identity
8. **Branding** — logos, favicon, AGENT.md
9. **Docs config** — docusaurus.config.ts
10. **CI additions** — license validation, dependency auditing
11. **Full build + test** — `mvn clean install`

## Verification

- `grep -r "dylibso\|chicory" --include="*.java" --include="*.xml" --include="*.yaml" --include="*.md" --include="*.ts" --include="*.toml"` — should return zero hits (except intentional attribution in NOTICE/README/blog)
- `mvn clean install` passes
- All module-info.java compile correctly with new module names

## BA Governance Checklist

Reference: `/home/andreatp/workspace/bytecodealliance-governance/projects/hosted/endive.md`

- [ ] README with BA branding
- [ ] CODE_OF_CONDUCT.md
- [ ] CODEOWNERS
- [ ] SECURITY.md updated
- [ ] CONTRIBUTING.md updated
- [ ] ADOPTERS.md
- [ ] Automated license validation in CI
- [ ] Dependency auditing in CI
- [ ] NOTICE file
- [ ] All links point to bytecodealliance/endive
