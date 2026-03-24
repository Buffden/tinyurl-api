# Phase 4: Frontend Implementation (Angular SPA)

## Objective

Implement and deploy a production-ready Angular Single Page Application (SPA) for URL shortening and management. The SPA will be served from AWS S3 via CloudFront CDN to provide fast, global access to the UI while maintaining complete separation from backend services.

## Depends On

- Phase 2: Core API (v1) - stable API contracts required
- Phase 3: Observability - CORS and monitoring integration

## Source References

- [ADR-006: Angular SPA + CDN Hosting Decision](../architecture/00-baseline/adr/)
- [HLD System Design - L1 System Context](../hld/system-design/system-design.md)
- [C4 Level 1 System Context Diagram](../lld/c4-level1-system-context.excalidraw)
- [API Contract](../lld/api-contract.md)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

## In Scope

- Angular 18+ SPA architecture (modules, routing, services)
- API client library with error handling and retry logic
- URL shortening workflow UI (create, view, copy, share)
- Responsive design (mobile, tablet, desktop)
- Performance optimization (lazy loading, tree-shaking, code splitting)
- Build pipeline and S3 deployment automation
- CloudFront SPA distribution configuration
- Security headers (CORS, CSP, X-Frame-Options, HSTS)

## Out of Scope

- User accounts/authentication (future feature)
- Analytics and tracking (future feature)
- Advanced features (custom domains, redirects management)
- Internationalization (i18n) - English only for v1
- Offline support (PWA) - defer to v2

## Architecture

### Component Structure

The app is organized under `src/app/` with four top-level directories: `core/` (singleton services, interceptors, models), `shared/` (reusable components and pipes), `features/shorten/` (the URL shortening form and results components), and `layout/` (navigation and footer). Each feature has its own module, component, and spec file. The `AppRoutingModule` lazy-loads `ShortenModule` at the root path `/`.

### Key Modules

| Module | Purpose | Lazy Loaded |
|--------|---------|------------|
| CoreModule | HTTP client, services, guards, interceptors | No (singleton) |
| SharedModule | Common components, pipes, utilities | No |
| ShortenModule | URL shortening form, results display | Yes |
| LayoutModule | Navigation, footer, theme | No |

## Implementation Details

### Step 1: Project Setup & Dependencies

**Tasks:**
- Create Angular project with `ng new` (Node 18+, npm 9+)
- Configure TypeScript strict mode
- Add dependencies: `@angular/common/http`, `rxjs`, `@angular/animations`
- Install dev dependencies: `prettier`, `eslint`, `karma`, `jasmine`
- Set up ESLint and code formatting rules
- Configure Webpack optimization for production builds

**Deliverables:**
- Angular project scaffolding
- tsconfig.json with strict settings
- package.json with all dependencies
- .eslintrc and prettier configuration

### Step 2: API Client Library

**Files:**
- `src/app/core/services/api.service.ts` - HTTP client wrapper
- `src/app/core/services/url.service.ts` - Business logic for URL operations
- `src/app/core/models/` - DTOs matching backend contracts
- `src/app/core/interceptors/api.interceptor.ts` - Error handling + retry logic

**Implementation:**
The `ApiService` wraps `HttpClient` and exposes a `createShortUrl(request)` method that posts to `/api/urls`. The `ApiInterceptor` attaches a generated `X-Correlation-Id` header on every outgoing request, maps 4xx/5xx errors to typed `ApiError` objects, and applies exponential backoff retry for 5xx and timeout errors (up to 3 attempts). Request timeout is set to 10 seconds via `RxJS timeout` operator.

**Features:**
- Centralized error handling (4xx, 5xx, timeout)
- Automatic retry logic (exponential backoff) for transient failures
- Request timeout configuration (10s default)
- Correlation ID header propagation (X-Correlation-Id)
- Response interceptor for logging and error transformation

**Deliverables:**
- API service with CRUD operations
- Typed request/response models
- Error handling interceptor with retry logic
- Correlation ID propagation for tracing

### Step 3: Routing & Components

**URL Shortening Workflow:**

1. **Shorten Page** (`/`)
   - Form: URL input (validation for HTTP/HTTPS), optional expiry selector
   - Error display (400, 409, 429 handling)
   - Success display: short URL, copy button, share buttons

2. **Copy to Clipboard**
   - Copy short URL to clipboard with feedback
   - Share buttons (Twitter, LinkedIn, Email)

3. **Route Configuration**
   Define routes in `AppRoutingModule`: the root path `/` lazy-loads `ShortenModule` which renders `ShortenUrlComponent`. Add a wildcard `**` route that redirects to `/` so unknown paths fall back to the shortener. CloudFront and any local dev server must also be configured to return `index.html` for all 404 responses to support client-side routing.

**SPA Routing Fallback:**
- Issue: Direct access to `/app/*` routes should load `index.html` (SPA pattern)
- Solution: Nginx + CloudFront behavior (map 404 to index.html)
- Angular handles route resolution client-side via Router

**Deliverables:**
- ShortenUrlComponent with form validation
- ResultsComponent for displaying shortened URL
- Error handling component for user-facing errors
- Responsive routing configuration

### Step 4: Form Validation & UX

**Validation Rules:**
- URL input: must be valid HTTP/HTTPS URL (max 2048 chars)
- Expiry (optional): positive integer, max 3650 days
- Real-time field validation feedback
- Disabled submit button while loading

**Error Handling:**
  - 400 Bad Request: Display validation error message
  - 409 Conflict: Custom alias already exists
  - 429 Too Many Requests: Rate limit exceeded, show retry countdown
  - 5xx Server Error: Show generic error, retry button

**UX Improvements:**
- Loading spinner during API call
- Toast notifications for success/error
- Keyboard accessibility (WCAG 2.1 AA)
- Mobile-responsive layout (viewport meta tag)

**Deliverables:**
- Reactive forms with real-time validation
- Error boundary component
- Toast notification service
- Accessibility audit passing

### Step 5: Performance Optimization

**Build Optimization:**
- Prod build with `ng build --prod`
- Enable TreeTree-shaking (remove unused code)
- Lazy loading for feature modules
- AOT (Ahead-of-Time) compilation enabled
- Bootstrap from main.ts (not index.html inline)

**Runtime Optimization:**
- OnPush change detection strategy for components
- TrackBy function in *ngFor loops
- Unsubscribe pattern (async pipe or takeUntil)
- Image optimization (WebP fallback)

**Deliverables:**
- Prod build < 500 KB (gzipped)
- First Contentful Paint < 2s (via CloudFront + CDN)
- Lighthouse performance score > 90

### Step 6: Security Hardening

**Content Security Policy (CSP):**

**CORS Configuration (Backend):**

**HTTP Security Headers:**
- `X-Content-Type-Options: nosniff` - Prevent MIME sniffing
- `X-Frame-Options: DENY` - Prevent clickjacking
- `Strict-Transport-Security: max-age=31536000` - Force HTTPS
- `Referrer-Policy: strict-origin-when-cross-origin` - Control referrer leaks

**Input Validation:**
- URL validation (RFC 3986 compliant)
- Sanitization of user input before rendering
- No dynamic script evaluation (eval forbidden)

**Deliverables:**
- Security headers in CloudFront distribution
- CSP policy configured in index.html
- Input validation and sanitization tested
- OWASP Top 10 checklist completed

### Step 7: Unit & E2E Testing

**Unit Tests:**
- Test coverage > 80% for services and components
- Mock HttpClientTestingModule for API calls
- Test error scenarios (timeouts, retries, 4xx/5xx)

**E2E Tests (Cypress/Playwright):**
- Happy path: shorten URL, copy, verify redirect
- Error scenarios: invalid URL, rate limiting, server errors
- Accessibility: tab navigation, screen reader compatibility

**Deliverables:**
- Unit test suite with > 80% coverage
- E2E test scenarios (happy path + error paths)
- CI/CD integration (GitHub Actions)

### Step 8: Build & Deployment Pipeline

**Build:**
Run `ng build --configuration production` to produce a minified, AOT-compiled output. This enables tree-shaking, removes dev-only code, and generates hashed filenames for cache busting. Set the `outputPath` in `angular.json` to `dist/tinyurl-frontend/`.

**Output:**
- `dist/tinyurl-frontend/` - Minified, optimized build
- Lazy-loaded chunks under `assets/chunks/`
- Source maps stored separately (optional for PROD)

**Deployment to S3:**
After a production build, sync the `dist/tinyurl-frontend/` directory to the target S3 bucket using the AWS CLI (`aws s3 sync`). Set `index.html` with `Cache-Control: no-cache` so browsers always fetch the latest entry point. All other assets (JS/CSS chunks) can be set to a long cache TTL since they have hashed filenames. After upload, invalidate the CloudFront distribution for `/*` or at minimum `/index.html` to ensure CDN edge nodes serve the new version immediately.

**Version Control:**
- Add `dist/` to `.gitignore`
- Build artifacts versioned in S3 (e.g., `v1.0.0/main.js`)
- Rollback: Deploy previous version from S3

**Deliverables:**
- Build script in package.json
- S3 upload automation (shell script or GitHub Actions)
- CloudFront cache invalidation procedure
- Rollback procedure documented

### Step 9: CloudFront Distribution Configuration

**AWS CloudFront Setup:**

**Origin 1: S3 (SPA Assets)**
- Domain: `tinyurl-spa-prod.s3.amazonaws.com`
- Origin path: `/latest`
- Restrict bucket access: Yes (use OAI)
- Allowed HTTP methods: GET, HEAD, OPTIONS

**Origin 2: Nginx/ALB (API Backend)**
- Domain: `api.go.buffden.com` (custom domain pointing to Nginx)
- Allowed HTTP methods: GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE
- Cache disabled for `/api/*` paths

**Cache Behaviors:**

| Path Pattern | Origin | TTL | Compress | Purpose |
|---|---|---|---|---|
| `/index.html` | S3 | 0 (no cache) | Yes | SPA root always fresh |
| `/main.*`, `/polyfills.*` | S3 | 1 year | Yes | Versioned chunks (cache busting via filename) |
| `/assets/*` | S3 | 1 month | Yes | Static assets |
| `/api/*` | Backend | 0 (no cache) | Yes | API calls, no caching |
| `/*` | S3 | 1 day | Yes | Default: serve from S3, fallback to index.html for 404 |

**Error Pages:**
- 403 Forbidden → `/index.html` (SPA fallback)
- 404 Not Found → `/index.html` (SPA fallback)
- 500 Internal Server Error → static error page

**Security:**
- Viewer protocol policy: `Redirect HTTP to HTTPS`
- Allowed HTTP methods: GET, HEAD, OPTIONS (+ POST, PUT for `/api/*`)
- Restrict Viewer Access: No (public content)
- AWS WAF: Optional (DDoS protection)

**Deliverables:**
- S3 bucket with OAI (Origin Access Identity)
- CloudFront distribution with S3 origin configured
- Cache behaviors for SPA + API routes
- Custom error pages for 403, 404 → index.html
- HTTPS certificate from AWS Certificate Manager

## Verification Steps

1. **Local Development:**
   - Run `ng serve` and open `http://localhost:4200`. The dev server proxies `/api/*` to the backend (configure `proxy.conf.json` pointing to `http://localhost:8080`). Submit a URL and verify a short link appears in the results component.

2. **Production Build:**
   - Run `ng build --configuration production` and confirm the `dist/` output is under 500 KB gzipped. Check that no `console.log` statements appear in the built output and that source maps are excluded from the production bundle.

3. **S3 + CloudFront Verification:**
   - Access SPA via CloudFront domain (should load index.html)
   - Verify assets are gzipped (check Content-Encoding header)
   - Test SPA routing: navigate to `/main.js` should 404 then fallback to index.html
   - Verify CORS preflight succeeds (`OPTIONS /api/urls`)

4. **Performance & Security:**
   - Lighthouse audit score > 90
   - PageSpeed Insights recommendation passed
   - OWASP Top 10 checklist completed
   - CSP policy report-only scan passes

## Deliverables

- [x] Angular project with modern tooling (npm, webpack, ng CLI)
- [x] API client library with retry, timeout, error handling
- [x] URL shortening form component with validation
- [x] Results component with copy/share functionality
- [x] SPA routing configuration with index.html fallback
- [x] Responsive CSS (mobile, tablet, desktop)
- [x] Unit tests > 80% coverage
- [x] E2E tests for happy path + error scenarios
- [x] Build optimization (< 500 KB gzipped)
- [x] S3 bucket and CloudFront distribution configured
- [x] Security headers (CSP, X-Frame-Options, HSTS)
- [x] Deployment automation (build + S3 upload script)
- [x] Rollback procedure documented

## Acceptance Criteria

- [ ] SPA loads and displays shortening form
- [ ] Form validation prevents invalid submissions
- [ ] API call matches backend `/api/urls` contract
- [ ] Shortened URL displays and can be copied
- [ ] CORS preflight succeeds without errors
- [ ] CloudFront serves SPA correctly (index.html fallback works)
- [ ] Assets are cached per CloudFront configuration
- [ ] Lighthouse score > 90
- [ ] HTTPS enforced (no mixed content)
- [ ] Security headers present (CSP, HSTS, X-Frame-Options)
- [ ] E2E tests pass for happy path, error, timeout scenarios
- [ ] Build < 500 KB gzipped
- [ ] CI/CD pipeline deploys automatically on push
