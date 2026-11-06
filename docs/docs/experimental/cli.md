---
sidebar_position: 5
sidebar_label: CLI
title: CLI
---
The experimental Endive CLI runs a Wasm module from the command line.

:::warning[Security Consideration]
The CLI gives the module the host's stdin, stdout and stderr. Do not run untrusted modules with it.
:::

It is available for download on Maven at the link:

```
https://repo1.maven.org/maven2/run/endive/cli-experimental/<version>/cli-experimental-<version>.sh
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
