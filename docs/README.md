# Website

This website is built using [Docusaurus](https://docusaurus.io/), a modern static website generator.

### Installation

```
$ npm install
```

### Local Development

```
$ npm run start
```

This command starts a local development server and opens up a browser window. Most changes are reflected live without having to restart the server.

### Build

```
$ npm run build
```

This command generates static content into the `build` directory and can be served using any static contents hosting service.


### Writing docs

- Describe how to use a feature, not how it is implemented. Internals belong in Javadoc or in the pull request description.
- No release-time or migration statements. Those go in a blog post; breaking changes relative to Chicory go in the migration guide.
- One code sample per option. Show a builder once per page.
- Body headings start at `##`; the frontmatter `title` is the page heading. Open every page with one plain sentence saying what it covers, which becomes its description in `llms.txt`.
- Names: Wasm, interpreter, runtime compiler, build-time compiler. Version placeholder: `${endive.version}`.
- Every page is executed by `npm test`: keep the hidden `//DEPS` and `docs.FileOps.writeResult(...)` blocks, and update `tests/approvals/` when a page moves.
