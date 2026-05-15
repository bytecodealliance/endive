---
sidebar_position: 1
sidebar_label: Installation
title: Installation
---
# Installation

Add Endive to your project using Maven or Gradle.

## Maven

Add the runtime dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>runtime</artifactId>
  <version>${endive.version}</version>
</dependency>
```

### Bill of Materials (BOM)

To keep the versions of different Endive artifacts aligned, use the provided BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>run.endive</groupId>
            <artifactId>bom</artifactId>
            <version>${endive.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then you can use any Endive dependency without declaring the version:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>runtime</artifactId>
</dependency>
```

## Gradle

```groovy
implementation 'run.endive:runtime:${endiveVersion}'
```

Or with the BOM:

```groovy
implementation platform('run.endive:bom:${endiveVersion}')
implementation 'run.endive:runtime'
```

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/getting-started", "installation.md.result", "empty");
```
-->
