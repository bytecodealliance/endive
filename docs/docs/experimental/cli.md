---
sidebar_position: 5
sidebar_label: CLI
title: CLI
---
# Install and use the CLI

:::warning[Security Consideration]
The experimental CLI uses `inheritSystem()` by default, granting the Wasm module full access to the host filesystem, environment, and stdio. Do not use it with untrusted modules in its current form.
:::

The experimental Endive CLI is available for download on Maven at the link:

```
https://repo1.maven.org/maven2/run/endive/cli/<version>/cli-<version>.sh
```

you can download the latest version and use it locally by typing:

```bash
export VERSION=$(curl -sS https://api.github.com/repos/bytecodealliance/endive/tags --header "Accept: application/json" | jq -r '.[0].name')
curl -L -o endive https://repo1.maven.org/maven2/run/endive/cli-experimental/${VERSION}/cli-experimental-${VERSION}.sh
chmod a+x endive
./endive
```

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/experimental", "cli.md.result", "empty");
```
-->
