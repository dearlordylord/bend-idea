# bend-format website

Static landing page for https://bend-format.dearlordylord.com/.
Cloudflare Pages project: `bend-format` (production branch: `master`).

Deploy from the repository root with an authenticated Wrangler session:

```sh
npx wrangler pages deploy website/bend-format --project-name bend-format --branch master
```

Unlike the Git-connected `bend-idea-plugins` project (which publishes `docs/`),
this project uses direct uploads; pushing to GitHub does not deploy it.
Register the custom domain in Cloudflare Pages, then point the `bend-format`
CNAME at `bend-format.pages.dev` with your DNS provider.
