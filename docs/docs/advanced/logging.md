---
sidebar_position: 4
sidebar_label: Logging
title: Logging
---
# Logging

For maximum compatibility and to avoid external dependencies we use, by default, the JDK Platform Logging ([JEP 264](https://openjdk.org/jeps/264)).
The Platform Logging falls back to the [`java.util.logging` API](https://docs.oracle.com/en/java/javase/21/core/java-logging-overview.html) by default.

For more advanced configuration scenarios we encourage you to provide an alternative, compatible, adapter:

- [SLF4J](https://www.slf4j.org/manual.html#jep264)
- [Log4j2](https://logging.apache.org/log4j/2.x/log4j-jpl.html)

It's also possible to provide a custom `run.endive.log.Logger` implementation if JDK Platform Logging is not available or doesn't fit.

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/advanced", "logging.md.result", "empty");
```
-->
