# BYD Trip Stats — marketing website

A self-contained static landing page (no build step, no framework) for advertising
the app. Plain HTML + CSS + vanilla JS, styled to match the app's DiLink "Ocean
Series" palette. Live at <https://byd-trip-stats.angoikon.workers.dev/>.

```
website/
├── index.html              # the landing page (schema.org structured data in <head>)
├── 404.html                # custom not-found page
├── styles.css              # all styling
├── script.js               # nav, scroll-reveal, screenshot placeholders, version pill
├── robots.txt              # crawl rules + sitemap pointer
├── sitemap.xml             # the page and its last-modified date
├── llms.txt                # plain-text summary for AI assistants and agents
├── _headers                # HTTP headers (security + caching)
├── .assetsignore           # files here that are not published (the READMEs)
└── assets/
    ├── logo.png            # app launcher icon (logo + favicon)
    └── screenshots/        # ← drop your screenshots here (see its README)
```

## Adding screenshots

See [`assets/screenshots/README.md`](assets/screenshots/README.md). Each frame shows
the exact filename it expects; drop a matching image in and it appears automatically.

## Preview locally

Any static server works, e.g.:

```bash
cd website
python3 -m http.server 8080
# open http://localhost:8080
```

## Deploy

The site is a static-assets-only Cloudflare Worker, set up by
[`wrangler.toml`](../wrangler.toml) at the repo root: it publishes this folder, and an
unknown path gets `404.html` with a 404 status. The Worker is connected to the GitHub
repo in the Cloudflare dashboard, which runs `npx wrangler deploy` on every push to
its production branch. To deploy by hand, run `npx wrangler deploy` from the repo root.

## Search and AI visibility: keep these in step

- **The site URL** is written in `index.html` (canonical, `og:url`, `og:image`, the
  JSON-LD), `robots.txt`, `sitemap.xml` and `llms.txt`. Moving to a custom domain
  means changing all of them and redirecting the old address with a 301.
- **`sitemap.xml`:** set `<lastmod>` to the deploy date whenever the page's content changes.
- **The FAQ** exists twice in `index.html`: the visible section and the `FAQPage`
  structured data in `<head>`. Change both together.
- **JSON-LD** (`<script type="application/ld+json">`) is not HTML-decoded: write a plain
  `&`, never `&amp;`. Check it after editing at <https://validator.schema.org/>.
- **On a release**, update the version pill's fallback text (`#appVersion` in
  `index.html`). `script.js` swaps in the latest GitHub release tag for visitors, but a
  crawler that doesn't run JavaScript reads the fallback.
- **`llms.txt`** summarises the page for AI tools; update it when features, models or
  the price change.
- **The `google-site-verification` meta tag** in `index.html` proves ownership to Google
  Search Console (and, through its import, to Bing Webmaster Tools). Don't remove it.
