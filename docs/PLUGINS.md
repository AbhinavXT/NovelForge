# Source plugins

NovelForge can load novel sources as JavaScript plugins, in the spirit of
LNReader. You can add a source without an app release: write a `.js` file,
host it in a repository (or install it straight from a file), and it shows
up in Search and Browse next to the built-in sources.

- Manage plugins in **Settings → Source plugins**.
- A complete, tested example: [`plugins/example-plugin.js`](plugins/example-plugin.js).
- A sample repository index: [`plugins/index.json`](plugins/index.json).

## Writing a plugin

A plugin is one file that assigns an object to `module.exports`:

```js
module.exports = {
  id: "my-site",              // stable and unique; never change it once published
  name: "My Site",
  version: "1.0.0",           // bump on every release; drives "Update" in the app
  site: "https://my-site.com",
  lang: "en",                 // optional, default "en"
  icon: "https://…/icon.png", // optional

  popularNovels: function (page) { /* → [NovelItem] */ },
  searchNovels: function (query, page) { /* → [NovelItem] */ },
  parseNovel: function (url) { /* → NovelDetails */ },
  parseChapter: function (url) { /* → HTML string */ }
};
```

`page` starts at 1. Return an empty array when there are no more results.

**NovelItem**: `{ name, url | path, cover?, author?, summary? }`

**NovelDetails**: `{ name, cover?, author?, summary?, status?, chapters: [{ name, url | path, number? }] }`

`number` defaults to the chapter's position in the list (1-based).

Use `url` for absolute links, or `path` for links relative to `site`
(LNReader style). Chapters go in reading order. `parseChapter` returns the
chapter's HTML; the app turns `<p>` paragraphs into reader text. Return
HTML even when you build the text yourself (one `<p>` per paragraph): plain
newlines are not preserved.

### Optional exports

```js
  // A filterable catalog for the Browse screen.
  filters: {
    categories: [{ label: "All", value: "" }, { label: "Fantasy", value: "fantasy" }],
    orderBys:   [{ label: "Popular", value: "popular" }],
    tags:       []
  },
  browseNovels: function (page, f) { /* f.category, f.orderBy, f.tag: a value or null */ },

  // Wait this long before each call, one call at a time (max 10 000).
  rateLimitMs: 500,

  // Keep "Translator: … Editor: …" lines and author-note containers (see below).
  stripCredits: false,
```

Any of the three filter lists may be empty or missing; the Browse screen
hides empty ones.

> ⚠️ The `id` becomes part of every novel ID saved in users' libraries.
> If you rename it, those novels lose their source.

## What a plugin can use

Plugin code is **synchronous**. There is no `async`/`await` and no Promises:
call `fetch` and use the result directly. The engine is Rhino with ES6
support: `let`/`const`, arrow functions, template literals and
`Array.prototype.map`/`filter`/`forEach` work. Classes, `async` and ES
modules do not.

| Global | Description |
|---|---|
| `fetch(url, init?)` | `init`: `{ method, headers, body }`. If `body` is an object it is form-encoded. Returns `{ status, ok, url, headers.get(name), text(), json() }`. Only `http(s)` URLs are allowed. Network failures throw an `Error` whose `name` is `"NetworkError"`. |
| `fetchText(url, init?)` | `fetch(...).text()`, but throws on a non-2xx status. |
| `fetchHtml(url, init?)` | Fetches and parses the page, with relative links resolved against the final URL. Throws on a non-2xx status. |
| `parseHtml(html, baseUrl?)` | Returns a **Node** for the document. |
| `encodeForm(obj)` | `a=1&b=2` encoding. |
| `escapeHtml(s)` | Escapes `& < > "`. |
| `paragraphsToHtml(list)` | `["a", "b"]` → `<p>a</p><p>b</p>` (escaped): return this from `parseChapter` when you build the text yourself. |
| `aesGcmDecrypt(key, iv, tag, data)` | AES-GCM decryption for sites that ship encrypted chapters. `key` is a UTF-8 string (16, 24 or 32 bytes), the rest base64. Returns the UTF-8 plaintext; throws on a bad key or tag. |
| `console.log/info/warn/error` | Shows up in logcat (tag `JsPlugin`) in debug builds. |

**Node** methods (backed by jsoup, CSS selectors):
`select(css)` → array of Node, `selectFirst(css)` → Node or `null`,
`text()`, `ownText()`, `wholeText()`, `html()`, `outerHtml()`, `tagName()`,
`attr(name)` → string or `null` (`attr(name, value)` sets it), `absUrl(name)` → absolute URL or `null`,
`hasClass(name)`, `children()`, `parent()`, `next()`, `prev()`, `remove()`,
`replaceWith(node)` (moves `node` into this one's place).

Requests go through the app's shared HTTP client: a browser User-Agent is
added if you don't set one, and requests are retried on 5xx and network
errors (GET only). Cloudflare challenges are solved automatically in a
hidden WebView, and the request is replayed with the clearance cookies.

Chapter HTML gets the same clean-up as the QuickNovel sources: author-note
containers (`div.qnauthornotecontainer`) and "Translator: … Editor: …"
credit lines are removed, unless the plugin exports `stripCredits: false`.

## Limits and sandbox

- No access to Java, files or Android APIs. The only ways out are the
  globals above.
- Each call has a 90-second budget (10 seconds to load the script). An
  infinite loop is stopped, and so is a call whose screen has closed.
- Scripts are capped at 2 MB and HTTP responses at 10 MB.
- Calls into the same plugin run one at a time; different plugins run in
  parallel.

Plugins still run code from the internet on the user's device and can make
requests to any site, so only add repositories you trust.

## Repositories

A repository is any URL that serves a JSON array (the same fields as
LNReader's `plugins.json`):

```json
[
  {
    "id": "my-site",
    "name": "My Site",
    "site": "https://my-site.com",
    "lang": "en",
    "version": "1.0.0",
    "url": "my-site.js",
    "iconUrl": "icons/my-site.png"
  }
]
```

A relative `url` or `iconUrl` is resolved against the index URL, so a GitHub
repo served through `raw.githubusercontent.com` or GitHub Pages works as-is.
The `id` in the index must match the `id` the script exports. When the
index's `version` is higher than the installed one, the app offers an update.

## Bundled plugins

The app's own sources ship as plugins in `app/src/main/assets/plugins/`,
listed in an `index.json` there. They install on first start and update
whenever the app ships a newer `version`. If the user removes one, it isn't
reinstalled, but it stays under "Available".

A bundled plugin that replaces a former built-in Kotlin source sets
`"sourceId"` in the bundled index to that source's id. It then registers
under that id, and may return `id` on list items, on the `parseNovel`
result and on chapters, so novels already in users' libraries keep their
ids (`rr_12345`, `rr_12345_67890`, …). An `id` is accepted only if it starts
with that source's prefix (`PluginManager.LEGACY_SOURCES`); otherwise it is
ignored and the default id is used. Downloaded repositories can't set
`sourceId`.

`BundledPluginsTest` checks that each bundled script loads and matches its
index entry. While a built-in source is being ported, compare the two
against the live site:

```sh
PARITY=royalroad ./gradlew :app:testDebugUnitTest --tests '*SourceParityTest*'
```

## Developing

1. Copy `plugins/example-plugin.js` and adapt the selectors.
2. Push it to your phone and install it with the file button in
   **Settings → Source plugins**. Reinstalling the same `id` replaces it.
3. Check logcat for `JsPlugin` lines: script errors are logged with the
   file name and line number.

The runtime is covered by `JsPluginTest`, which also loads the example
plugin against canned HTML. That is a quick way to test a plugin's
selectors on the JVM before going near a device.
