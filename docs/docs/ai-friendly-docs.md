---
sidebar_position: 90
sidebar_label: AI-Friendly Docs
title: AI-Friendly Documentation
---

Endive documentation is designed to be easily consumed by LLMs, AI agents, and developer tools. All machine-readable files are auto-generated at build time from the same source as the human-facing site, using the [llms.txt specification](https://llmstxt.org/).

## Available Endpoints

| Endpoint | Description |
|----------|-------------|
| [`/llms.txt`](https://endive.run/llms.txt) | Lightweight index of all docs and blog posts with one-line descriptions |
| [`/llms-full.txt`](https://endive.run/llms-full.txt) | Full documentation content concatenated into a single file |
| [`/sitemap.xml`](https://endive.run/sitemap.xml) | Standard sitemap for crawler discovery |
| [`/robots.txt`](https://endive.run/robots.txt) | Permits all crawlers including AI agents |

Every documentation page also has a **raw Markdown** version available by appending `.md` to its path. For example:

- HTML: [`/docs/core/host-functions`](https://endive.run/docs/core/host-functions)
- Markdown: [`/docs/core/host-functions.md`](https://endive.run/docs/core/host-functions.md)

## Usage Examples

### Feed Endive docs to an LLM in one shot

Fetch `llms-full.txt` and include it in your prompt context:

```bash
curl -s https://endive.run/llms-full.txt | wc -c
# ~86 KB — fits easily in any modern LLM context window
```

### Let an AI agent discover relevant pages

An agent can fetch `llms.txt` first to find the right page, then fetch only that page's Markdown:

```bash
# 1. Fetch the index
curl -s https://endive.run/llms.txt

# 2. Fetch a specific page as clean Markdown
curl -s https://endive.run/docs/core/host-functions.md
```

### Configure AI coding tools

Many AI-powered development tools support `llms.txt` natively. Point them to `https://endive.run/llms.txt` to give them access to the full Endive documentation.

## What Gets Included

- All **documentation pages** covering installation, core concepts, compilation, WASI, security, and more
- All **published blog posts**
- Content is cleaned: front matter and test harness comments are stripped, leaving only the documentation text

These files are regenerated on every site build, so they always reflect the latest documentation.

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs", "ai-friendly-docs.md.result", "empty");
```
-->
