# ADR-007: Frontend Rendering Strategy

**Status**: Accepted
**Date**: March 2026
**Deciders**: Architecture review

---

## Background: Angular Rendering Modes

Angular supports three rendering strategies. Understanding the tradeoffs is necessary to select the right one per route.

### 1. Client-Side Rendering (CSR) — Pure SPA

The server sends a near-empty `index.html`. The browser downloads JavaScript, bootstraps Angular, and renders the page entirely on the client.

**Pros**
- Simplest deployment: static files only, no server process
- Works perfectly on S3 + CloudFront
- No build-time route enumeration required

**Cons**
- Crawlers receive an empty shell; SEO is poor for public pages
- First Contentful Paint (FCP) is delayed until JS is parsed and executed
- Core Web Vitals (LCP, CLS) are harder to optimise

**When to use**: Auth-protected pages where crawlers never land and SEO is irrelevant.

---

### 2. Static Site Generation (SSG) / Prerendering

Angular renders specific routes at **build time** and emits static HTML files. Those files are uploaded to S3 and served directly by CloudFront. The page arrives as fully-formed HTML; Angular then hydrates it on the client.

**Pros**
- Full SEO: crawlers receive real HTML with content and meta tags
- Fastest possible TTFB: CloudFront serves pre-built HTML from edge cache
- No Node.js server required — compatible with S3 + CloudFront
- Core Web Vitals are excellent for prerendered pages

**Cons**
- Only works for routes known at build time (static route list)
- Personalised or user-specific content cannot be prerendered
- Any content change on a prerendered page requires a new build and deploy

**When to use**: Public marketing and utility pages with stable, non-personalised content.

---

### 3. Server-Side Rendering (SSR) — Angular Universal

A Node.js process renders each request on the server and streams HTML to the client. The client then hydrates.

**Pros**
- Full SEO on dynamic, data-driven routes
- Content is always fresh — no stale build artifacts

**Cons**
- Requires a live Node.js server (EC2, ECS, Lambda, etc.)
- **Incompatible with S3 + CloudFront static hosting**
- Higher infrastructure cost and operational complexity
- Cold start latency (especially on Lambda)
- Adds a new runtime failure surface

**When to use**: Public pages whose content changes per-request and cannot be prerendered (e.g. `/u/:username` profiles if they must be crawlable and personalised).

---

### 4. Hydration

Hydration is not a rendering mode — it is the process that bridges server-rendered (SSG or SSR) HTML and a live Angular application in the browser.

When a prerendered page is delivered, the browser receives fully-formed HTML and paints it immediately. At this point Angular's JavaScript has not run yet, so the page is static — no event listeners, no routing, no reactivity. Hydration is how Angular becomes interactive without destroying and re-rendering the existing DOM.

#### Without hydration (destructive bootstrap)

```text
Browser receives HTML → Angular boots → discards DOM → re-renders from scratch → attaches listeners
```

The user sees a flash as the DOM is torn down and rebuilt. LCP and CLS metrics suffer, negating the benefit of pre-rendering.

#### With hydration (non-destructive bootstrap)

```text
Browser receives HTML → Angular boots → walks existing DOM → attaches listeners in place
```

No visual disruption. The pre-rendered HTML stays in place and becomes interactive as Angular reconciles it.

Hydration is enabled in Angular 17+ via `provideClientHydration()` in the app config:

```typescript
// app.config.ts
import { provideClientHydration } from '@angular/platform-browser';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideClientHydration(),
  ]
};
```

#### Applicability per route type

| Route type | Hydration relevant? | Reason |
|---|---|---|
| SSG (public routes) | Yes — required | Pre-rendered HTML must be hydrated, not re-rendered, to preserve Core Web Vitals gains |
| CSR (auth routes) | No | No server-rendered DOM exists to hydrate; Angular renders normally |
| SSR | Yes — required | Same principle as SSG, but HTML is generated per-request rather than at build time |

---

## Context

The frontend is an Angular SPA deployed to S3 + CloudFront (see ADR-006). There is no Node.js server available, which rules out runtime SSR. The application has two distinct categories of routes:

1. **Public static routes** — landing page, about, pricing, auth pages, 404. Content is stable and non-personalised. These benefit from SEO and fast load times.
2. **Auth-protected routes** — dashboard, profile, settings, analytics. No crawler ever lands here. SEO is irrelevant.

Additionally, short URL redirects (`/:shortCode`) are handled entirely by the Spring Boot backend and never reach the Angular app (see ADR-003). There is no Angular route for short codes.

---

## Decision

Apply **mixed rendering**: SSG for public routes, CSR for auth-protected routes.

| Route | Strategy | Reason |
|---|---|---|
| `/` | SSG | Primary SEO target; stable content |
| `/about` | SSG | Crawlable marketing page |
| `/pricing` | SSG | Crawlable marketing page |
| `/login` | SSG | Indexed by some crawlers; stable layout |
| `/register` | SSG | Indexed by some crawlers; stable layout |
| `/404` | SSG | Custom error page served by CloudFront |
| `/dashboard` | CSR | Auth-protected; no SEO requirement |
| `/dashboard/:id/analytics` | CSR | Auth-protected; personalised data |
| `/profile` | CSR | Auth-protected; personalised data |
| `/settings` | CSR | Auth-protected; personalised data |

Angular's `@angular/ssr` prerender flag (`ng build --prerender`) generates the SSG routes at build time. No server runtime is involved. The rest of the routes hydrate as normal CSR.

---

## Consequences

Positive:
- Public pages have full SEO with real HTML delivered from CloudFront edge
- No server infrastructure required — consistent with the static hosting constraint
- Auth-protected pages load as fast SPA transitions after initial app bootstrap
- CI pipeline stays simple: `ng build --prerender` → `aws s3 sync` → CloudFront invalidation

Negative / Tradeoffs:
- Content changes on prerendered pages (e.g. pricing copy) require a new build and deploy to take effect
- Prerendered pages must not include any personalised or runtime-dynamic content
- `angular.json` must maintain an explicit `routes.txt` listing all SSG routes — easy to forget when adding new public routes

---

## Future Consideration

If `/u/:username` (public user profiles) or `/preview/:shortCode` (link preview before redirect) are built and require SEO, they cannot be prerendered (content is per-user/per-link and not known at build time). At that point, the options are:

1. Accept no SEO on those routes (simplest)
2. Add a Lambda@Edge or CloudFront Function to serve a prerendered shell to crawlers only
3. Add a separate SSR runtime (Node.js on Lambda or ECS) scoped to those routes only

This decision should be made under a new ADR when those features are scoped.

---

## Alternatives Considered

| Option | Why Rejected |
|---|---|
| Pure CSR for all routes | Poor SEO on public marketing pages; worse Core Web Vitals |
| SSR (Angular Universal) for all routes | Requires Node.js server; incompatible with S3 + CloudFront static hosting |
| SSG for all routes including dashboard | Auth-protected routes have personalised content; prerendering them is not possible |
| Third-party prerender service (Prerender.io) | Additional vendor dependency and cost; unnecessary when Angular's native SSG covers the requirement |
