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

```
tinyurl-frontend/
├── src/
│   ├── app/
│   │   ├── core/
│   │   │   ├── services/
│   │   │   │   ├── url.service.ts
│   │   │   │   ├── api.service.ts
│   │   │   │   └── error-handling.service.ts
│   │   │   ├── guards/
│   │   │   └── interceptors/
│   │   │       └── api.interceptor.ts
│   │   ├── shared/
│   │   │   ├── components/
│   │   │   ├── pipes/
│   │   │   └── models/
│   │   ├── features/
│   │   │   ├── shorten/
│   │   │   │   ├── shorten-url.component.ts
│   │   │   │   ├── shorten-url.component.html
│   │   │   │   └── shorten-url.component.css
│   │   │   └── redirect/
│   │   │       └── redirect.component.ts
│   │   ├── layout/
│   │   ├── app-routing.module.ts
│   │   └── app.component.ts
│   ├── assets/
│   ├── styles/
│   └── index.html
├── angular.json
├── tsconfig.json
└── package.json
```

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
```typescript
// api.service.ts
export class ApiService {
  constructor(private http: HttpClient) {}
  
  shortenUrl(request: ShortenUrlRequest): Observable<CreateUrlResponse> {
    return this.http.post<CreateUrlResponse>('/api/urls', request)
      .pipe(
        retry({ count: 2, delay: 1000 }),
        timeout(10000),
        catchError(this.handleError)
      );
  }
  
  resolveUrl(shortCode: string): Observable<RedirectResponse> {
    // Client-side redirect resolution for analytics
    return this.http.get<RedirectResponse>(`/api/urls/${shortCode}`);
  }
}
```

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
   ```typescript
   const routes: Routes = [
     { path: '', component: ShortenComponent },
     { path: '**', redirectTo: '' }
   ];
   ```

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
```
Content-Security-Policy: 
  default-src 'self';
  script-src 'self' 'nonce-{random}';
  style-src 'self' 'nonce-{random}';
  img-src 'self' data:;
  font-src 'self';
  connect-src 'self' api.tinyurl.buffden.com;
  frame-ancestors 'none';
```

**CORS Configuration (Backend):**
```yaml
# application.yaml (Spring CORS)
cors:
  allowedOrigins: "https://tinyurl.buffden.com,https://www.tinyurl.buffden.com"
  allowedMethods: "GET,POST,OPTIONS"
  allowedHeaders: "Content-Type,X-Correlation-Id"
  maxAge: 3600
```

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
```bash
npm install
npm run lint
npm run test
npm run build
```

**Output:**
- `dist/tinyurl-frontend/` - Minified, optimized build
- Lazy-loaded chunks under `assets/chunks/`
- Source maps stored separately (optional for PROD)

**Deployment to S3:**
```bash
# Upload to S3 with versioning
aws s3 sync dist/tinyurl-frontend/ s3://tinyurl-spa-prod --delete

# Invalidate CloudFront cache
aws cloudfront create-invalidation \
  --distribution-id ${CLOUDFRONT_DIST_ID} \
  --paths "/*"
```

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
- Domain: `api.tinyurl.buffden.com` (custom domain pointing to Nginx)
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
   ```bash
   ng serve --open
   # Navigate to http://localhost:4200
   # Form submission should call backend API
   ```

2. **Production Build:**
   ```bash
   ng build --prod
   npm run build:stats  # Verify bundle size
   npm run test  # Run unit tests
   npm run e2e  # Run E2E tests
   ```

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
