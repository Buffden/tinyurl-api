# Phase 9: Hardening (Security & Production Readiness)

## Objective

Prepare the v2 system for production with security validation, performance verification, and operational readiness.

## MVP-Only Execution Rule

- For the current timeline, implement only content before `## Optional (Post-MVP)`.
- Treat `## Optional (Post-MVP)` items as backlog and do not block release.

## Depends On

- [Phase 1 Foundation](PHASE_1_FOUNDATION.md)
- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 3 Observability](PHASE_3_OBSERVABILITY.md)
- [Phase 5 Cache Layer (v2)](PHASE_5_CACHE_LAYER_V2.md)
- [Phase 6 Rate Limiting (v2)](PHASE_6_RATE_LIMITING_V2.md)
- [Phase 7 Custom Aliases (v2)](PHASE_7_CUSTOM_ALIASES_V2.md)
- [Phase 8 Cleanup and Archival (v2)](PHASE_8_CLEANUP_AND_ARCHIVAL_V2.md)

## Source References

- [Threat Model](../security/threat-model.md)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)
- [LLD C4 Diagrams](../lld/c4-diagrams.md)
- [ADR-005 Technology Stack](../architecture/00-baseline/adr/ADR-005-technology-stack.md)

## In Scope

- security hardening and threat remediation
- load/performance and reliability testing
- deployment and runbook readiness
- final documentation consistency check

## Out of Scope

- major feature additions
- architecture rewrites unrelated to hardening goals

## Execution Steps

### Step 1: Security remediation pass

References:

- [Threat Model](../security/threat-model.md)

Tasks:

- Validate controls for injection, abuse, and data exposure paths
- Close critical/high-risk items before release

### Step 2: Performance and resilience validation

References:

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

Tasks:

- Run load tests for v1/v2 targets
- Validate latency and error-rate SLOs
- Exercise failover/degraded scenarios

### Step 3: Deployment readiness

References:

- [LLD C4 Diagrams](../lld/c4-diagrams.md)

Tasks:

- Validate runtime configuration and secrets handling
- Ensure operational runbook coverage for incident cases

### Step 4: Final documentation sync

References:

- [Requirements README](../requirements/README.md)
- [HLD README](../hld/README.md)
- [LLD README](../lld/README.md)

Tasks:

- Confirm docs match implemented behavior
- Freeze release notes and known limitations

## Deliverables

- security review evidence and resolved findings list
- load test report and tuning notes
- production readiness checklist
- synchronized final docs/runbook

## Acceptance Criteria

- No unresolved critical/high security findings
- System meets latency and reliability targets
- Operational readiness validated for go-live

---

## Part 2: Implementation Deep Dive

### Security Hardening Checklist

**1. Input Validation & Output Encoding**

Target URL validation with URL parsing:

```java
@Component
public class UrlValidationService {
	private static final int MAX_URL_LENGTH = 2048;
	private static final Pattern VALID_URL_PATTERN = 
		Pattern.compile("^https?://[\\w\\-\\.]+(:\\d+)?(/.*)?$", Pattern.CASE_INSENSITIVE);

	public ValidationResult validateTargetUrl(String urlString) {
		List<String> errors = new ArrayList<>();

		// 1. Null & length checks
		if (urlString == null || urlString.trim().isEmpty()) {
			errors.add("URL cannot be empty");
			return new ValidationResult(false, errors);
		}

		if (urlString.length() > MAX_URL_LENGTH) {
			errors.add(String.format("URL exceeds maximum length of %d", MAX_URL_LENGTH));
			return new ValidationResult(false, errors);
		}

		// 2. Scheme validation (http/https only)
		try {
			URI uri = new URI(urlString);
			String scheme = uri.getScheme();

			if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
				errors.add("Only HTTP(S) schemes are allowed");
				return new ValidationResult(false, errors);
			}

			// 3. Block localhost/private IPs (DNS rebinding protection)
			String host = uri.getHost();
			if (isPrivateOrLoopback(host)) {
				errors.add("URLs pointing to localhost or private networks are not allowed");
				return new ValidationResult(false, errors);
			}

			// 4. Validate port (block common attack ports)
			int port = uri.getPort();
			if (port > 0 && isRestrictedPort(port)) {
				errors.add(String.format("Port %d is restricted", port));
				return new ValidationResult(false, errors);
			}

			return new ValidationResult(true, errors);

		} catch (URISyntaxException e) {
			errors.add("Invalid URL format: " + e.getMessage());
			return new ValidationResult(false, errors);
		}
	}

	private boolean isPrivateOrLoopback(String host) {
		try {
			InetAddress addr = InetAddress.getByName(host);
			return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || 
				   addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
		} catch (UnknownHostException e) {
			// If DNS resolution fails, block to be safe
			return true;
		}
	}

	private boolean isRestrictedPort(int port) {
		// Block SMTP (25), SSH (22), DNS (53), NTP (123), etc.
		int[] restricted = {22, 23, 25, 53, 69, 123, 135, 139, 445, 1433, 3306, 5432, 6379};
		return Arrays.stream(restricted).anyMatch(p -> p == port);
	}
}
```

HTML entity encoding in responses:

```java
@Component
public class ResponseEncoder {
	public String encodeForHtml(String input) {
		if (input == null) return null;
		return input.replace("&", "&amp;")
					.replace("<", "&lt;")
					.replace(">", "&gt;")
					.replace("\"", "&quot;")
					.replace("'", "&#x27;");
	}

	public String encodeForJson(String input) {
		if (input == null) return "null";
		// Jackson ObjectMapper handles JSON escaping automatically
		try {
			return new ObjectMapper().writeValueAsString(input);
		} catch (JsonProcessingException e) {
			return "\"\"";
		}
	}
}
```

**2. SQL Injection Prevention**

Verify all database queries use prepared statements (Hibernate/JPA default):

```java
// ✅ SAFE: Using JpaRepository with method name queries (prepared statements)
@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {
	Optional<Url> findByAlias(String alias);  // Parameterized internally
}

// ✅ SAFE: Using @Query with parameter binding
@Query("SELECT u FROM Url u WHERE u.alias = :alias AND u.isDeleted = false")
Optional<Url> findActiveByAlias(@Param("alias") String alias);

// ❌ NEVER DO THIS: String concatenation
// String query = "SELECT * FROM urls WHERE alias = '" + alias + "'";

// Audit script to detect vulnerable queries
@Service
public class SqlAuditService {
	public void auditForSqlInjection() {
		List<String> vulnerablePatterns = Arrays.asList(
			"String.format.*SELECT",
			"\\+ .*SELECT",
			"con.createStatement()",  // Not PreparedStatement
			"\".*WHERE.*\" \\+"
		);
        
		// Scan source code for patterns
		log.info("SQL injection audit: verify all queries use PreparedStatement/JPA");
	}
}
```

**3. CORS & Cross-Origin Security**

Spring Security CORS configuration:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.cors(cors -> cors.configurationSource(corsConfigurationSource()))
			.csrf(csrf -> csrf.disable())  // Stateless API doesn't need CSRF
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives(
					"default-src 'self'; " +
					"script-src 'self'; " +
					"style-src 'self' 'unsafe-inline'; " +
					"img-src 'self' data: https:; " +
					"font-src 'self'; " +
					"connect-src 'self' https://api.example.com; " +
					"frame-ancestors 'none'; " +
					"base-uri 'self'; " +
					"form-action 'self'; "
				))
				.frameOptions(frame -> frame.deny())  // X-Frame-Options: DENY
				.xssProtection(xss -> xss.block(true)) // X-XSS-Protection: 1; mode=block
				.contentTypeOptions(cto -> cto.block()) // X-Content-Type-Options: nosniff
				.strictTransportSecurity(hsts -> hsts
					.maxAgeInSeconds(31536000)  // 1 year
					.includeSubDomains(true)
					.preload(true)  // HSTS preload list
				)
			)
			.authorizeHttpRequests(authz -> authz
				.requestMatchers("/actuator/health/**").permitAll()
				.requestMatchers("/api/urls/{id}").permitAll()  // Public redirect
				.anyRequest().authenticated()
			);

		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(Arrays.asList(
			"https://app.example.com",
			"https://admin.example.com"
			// NEVER use "*" for credentials-enabled APIs
		));
		config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(Arrays.asList("Content-Type", "Authorization"));
		config.setExposedHeaders(Arrays.asList("X-RateLimit-Limit", "X-RateLimit-Remaining"));
		config.setMaxAge(3600L);
		config.setAllowCredentials(false);  // Stricter: no credential cookies

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}
}
```

**4. Authentication & Authorization Scope**

No additional authentication mechanism is introduced in this phase.

- Keep public endpoints as currently designed (create + redirect).
- Restrict actuator and internal operational endpoints using existing Spring profile and network policy.
- Defer API key or OAuth flows unless explicitly added as a future requirement.

**5. Sensitive Data Protection**

Mask sensitive fields in logs:

```java
@Component
public class SensitiveDataMasker {

	public String maskUrl(String url) {
		try {
			URI uri = new URI(url);
			// Mask query parameters that might contain sensitive data
			if (uri.getQuery() != null) {
				String[] params = uri.getQuery().split("&");
				StringBuilder masked = new StringBuilder();
				for (String param : params) {
					if (param.contains("=")) {
						String key = param.split("=")[0];
						if (isSensitiveParameter(key)) {
							masked.append(key).append("=***&");
						} else {
							masked.append(param).append("&");
						}
					}
				}
				return masked.toString();
			}
		} catch (URISyntaxException e) {
			// If parsing fails, mask entire query string
			return url.replaceAll("\\?.*$", "?***");
		}
		return url;
	}

	private boolean isSensitiveParameter(String param) {
		String[] sensitiveParams = {
			"password", "token", "secret", "api_key", "credential", 
			"auth", "session", "ssn", "email", "phone"
		};
		String lowerParam = param.toLowerCase();
		return Arrays.stream(sensitiveParams).anyMatch(lowerParam::contains);
	}
}
```

Filter sensitive fields from error responses:

```java
@RestControllerAdvice
public class SensitiveExceptionHandler {

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleException(Exception ex, HttpServletRequest request) {
		// Log full exception internally (with all details)
		log.error("Unexpected error on {}", maskUrl(request.getRequestURI()), ex);

		// Return safe error response to client (no stack traces, no SQL details)
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
			ApiError.builder()
				.code("INTERNAL_ERROR")
				.message("An unexpected error occurred")
				// Never expose: .details(ex.getMessage())
				.build()
		);
	}

	private String maskUrl(String url) {
		// Use SensitiveDataMasker
		return url;
	}
}
```

**6. HTTP Security Headers**

Configure all security headers in Spring Security (see SecurityConfig above):

- `Strict-Transport-Security` (HSTS): Enforce HTTPS
- `X-Frame-Options: DENY`: Prevent clickjacking
- `X-Content-Type-Options: nosniff`: Prevent MIME sniffing
- `X-XSS-Protection: 1; mode=block`: XSS protection
- `Content-Security-Policy`: Restrict resource loading
- `Referrer-Policy: strict-origin-when-cross-origin`: Limit referrer leakage

---

### Performance & Resilience Testing

**Load Test Plan (using k6):**

```javascript
// load-test.js (k6 script)
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

export const errorRate = new Rate('errors');

export const options = {
	stages: [
		{ duration: '30s', target: 10 },    // Ramp to 10 VUs
		{ duration: '1m', target: 100 },    // Ramp to 100 VUs
		{ duration: '5m', target: 500 },    // Sustained at 500 VUs (1000 RPS @ 2 ops/user)
		{ duration: '30s', target: 0 },     // Ramp down
	],
	thresholds: {
		'http_req_duration': ['p(95)<500', 'p(99)<1000'],  // 95th % < 500ms, 99th % < 1s
		'http_req_failed': ['rate<0.01'],  // Error rate < 1%
		'errors': ['rate<0.05'],           // Custom error rate < 5%
	},
};

export default function () {
	const baseUrl = 'http://localhost:8080';
	const aliasLength = 12;
	const alias = generateRandomAlias(aliasLength);
	const targetUrl = 'https://example.com/page' + Math.floor(Math.random() * 10000);

	// Test: Create short URL (with optional custom alias)
	let createResponse = http.post(`${baseUrl}/api/urls`, JSON.stringify({
		targetUrl: targetUrl,
		customAlias: Math.random() > 0.8 ? generateRandomAlias(8) : undefined,
		ttlMinutes: 10080,  // 1 week
	}), {
		headers: { 'Content-Type': 'application/json' },
	});

	check(createResponse, {
		'create status is 201': (r) => r.status === 201,
		'create response time OK': (r) => r.timings.duration < 500,
	}) || errorRate.add(1);

	let createdUrl = JSON.parse(createResponse.body);
	if (createdUrl.alias) {
		// Test: Redirect (GET)
		let redirectResponse = http.get(`${baseUrl}/${createdUrl.alias}`);
        
		check(redirectResponse, {
			'redirect status is 302': (r) => r.status === 302,
			'redirect response time OK': (r) => r.timings.duration < 100,
		}) || errorRate.add(1);
	}

	// Test: Rate limiting
	if (Math.random() > 0.9) {
		for (let i = 0; i < 10; i++) {
			http.post(`${baseUrl}/api/urls`, JSON.stringify({
				targetUrl: 'https://example.com/spam' + i,
				ttlMinutes: 10080,
			}), {
				headers: { 'Content-Type': 'application/json' },
			});
		}
	}
}

function generateRandomAlias(length) {
	const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
	let result = '';
	for (let i = 0; i < length; i++) {
		result += chars.charAt(Math.floor(Math.random() * chars.length));
	}
	return result;
}
```

**Run Load Test:**

```bash
# Install k6: https://k6.io/docs/getting-started/installation/

# Run test
k6 run load-test.js

# Run with custom VU scaling
k6 run -u 500 -d 5m load-test.js

# Export results to JSON
k6 run --out json=results.json load-test.js
```

**Failure Scenario Testing:**

```java
@SpringBootTest
@ActiveProfiles("test")
public class ResilientFailureTest {

	@Test
	public void testCacheFailureDoesNotBlockRequests() {
		// Cache is down, but requests should fallback to DB
		cacheService.simulateFailure();
        
		CreateUrlRequest request = new CreateUrlRequest("https://example.com");
		ResponseEntity<Url> response = restTemplate.postForEntity("/api/urls", request, Url.class);
        
		assertEquals(HttpStatus.CREATED, response.getStatusCode());
		assertNotNull(response.getBody().getAlias());
	}

	@Test
	public void testDatabaseFailureReturnsUnavailable() {
		urlRepository.simulateConnectionFailure();
        
		CreateUrlRequest request = new CreateUrlRequest("https://example.com");
		ResponseEntity<ApiError> response = restTemplate.postForEntity(
			"/api/urls", request, ApiError.class
		);
        
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
	}
}
```

---

### Deployment Readiness & Operational Procedures

**Configuration Management (Environment + Profile Based):**

```yaml
# application-prod.yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}

# Never commit real values; provide examples in .env.example only.
```

**Health Check Endpoints:**

```java
@RestController
@RequestMapping("/actuator/health")
public class HealthCheckController {

	@GetMapping("/live")
	public ResponseEntity<Map<String, String>> liveness() {
		// Simple check: application is running
		return ResponseEntity.ok(Map.of("status", "UP"));
	}

	@GetMapping("/ready")
	public ResponseEntity<Map<String, String>> readiness() {
		// Check dependencies
		Map<String, String> status = new LinkedHashMap<>();
		status.put("status", "UP");

		// Database connection check
		if (!isDatabaseHealthy()) {
			status.put("status", "DOWN");
			status.put("database", "DISCONNECTED");
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(status);
		}

		// Redis connection check
		if (!isRedisHealthy()) {
			status.put("status", "DEGRADED");
			status.put("redis", "DISCONNECTED");
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(status);
		}

		status.put("database", "OK");
		status.put("redis", "OK");
		return ResponseEntity.ok(status);
	}

	private boolean isDatabaseHealthy() {
		try {
			// Quick query to verify connectivity
			return !jdbcTemplate.queryForObject(
				"SELECT 1", Integer.class).equals(0);
		} catch (Exception e) {
			return false;
		}
	}

	private boolean isRedisHealthy() {
		try {
			redisTemplate.getConnectionFactory().getConnection().ping();
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
```

**Production Incident Runbook:**

```markdown
## Incident Playbook

### Symptom: High Error Rate (429 Too Many Requests)
- **Root Cause**: Potential abuse/DDoS, or legitimate traffic spike
- **Check**: 
  - Monitor `/actuator/metrics/rate_limit_exceeded` metric
  - Query logs for suspicious IPs: `jq '.client_ip' logs.json | sort | uniq -c`
- **Action**:
  - Temporary: Increase rate limits in application-prod.yaml + redeploy
  - Long-term: Configure Nginx WAF rules to block suspicious patterns
  - If DDoS: Enable CloudFront geo-blocking, enable WAF

### Symptom: Database Response Time > 1s (p99)
- **Root Cause**: Query inefficiency, missing indexes, or connection pool exhaustion
- **Check**:
  - `SHOW PROCESSLIST` in PostgreSQL, look for long-running queries
  - Check connection pool: `SELECT sum(numbackends) FROM pg_stat_database`
  - Analyze slow queries: `SELECT query, mean_time FROM pg_stat_statements ORDER BY mean_time DESC`
- **Action**:
  - Kill long-running transaction: `SELECT pg_terminate_backend(pid) WHERE ...`
  - Add missing index: `CREATE INDEX idx_urls_expiry ON urls(expiry_time) WHERE is_deleted = false`
  - Increase connection pool: `hikari.maximum-pool-size: 20` (default: 10)

### Symptom: Redis Connection Failures
- **Root Cause**: Redis memory exhausted, network partition, or service restart
- **Check**:
  - `redis-cli info memory`
  - `redis-cli latency doctor`
	- Check Redis service logs for restart/failover events
- **Action**:
  - Increase Redis memory or enable eviction policy
  - Restart Redis: `redis-cli shutdown && redis-server /etc/redis/redis.conf`
  - Application falls back to DB (degraded but functional)

### Symptom: Cleanup Job Failure (URLs not being deleted)
- **Root Cause**: Job not acquiring lock, transaction timeout, or cascade failures
- **Check**:
  - `SELECT COUNT(*) FROM urls WHERE is_deleted = false AND expiry_time < NOW() - INTERVAL '2 days'`
  - Check logs: `grep "cleanup_job" logs.json | tail -100`
  - Redis lock status: `redis-cli GET cleanup:lock`
- **Action**:
  - Force release lock: `redis-cli DEL cleanup:lock`
  - Increase batch size gradually: `cleanup.batch-size: 500` then `1000`
  - Run manual cleanup script (during low-traffic window)
```

---

### Documentation & Release Readiness

**Production Deployment Checklist:**

```markdown
## Pre-Production Verification Checklist

**Security & Compliance (CRITICAL)**
- [ ] All critical/high security scan findings resolved
- [ ] SQL injection audit completed (no string concatenation in queries)
- [ ] CORS policy configured for production origins only
- [ ] HTTPS/TLS configured with valid certificate (not self-signed)
- [ ] Secrets injected via environment variables (not in source files)
- [ ] Database credentials rotated (not default from template)
- [ ] Load test completed: p95 latency < 500ms at 1000 RPS

**Operational Readiness**
- [ ] Metrics dashboard created (latency, error rate, cache hit rate)
- [ ] Alert routing configured to the team channel
- [ ] Backup strategy tested (PostgreSQL PITR working)
- [ ] Rollback procedure documented and tested
- [ ] Database index optimization completed
- [ ] Nginx configuration reviewed (WAF rules, rate limits)
- [ ] SSL/TLS certificate expiration monitoring enabled

**Documentation & Runbooks**
- [ ] Production runbook completed with incident playbooks
- [ ] Architecture docs match deployed infrastructure
- [ ] API contract frozen and versioned
- [ ] Known limitations documented
- [ ] Feature flag configuration documented

**Final Verification**
- [ ] Performance SLOs verified: p99 latency < 1s, error rate < 0.1%
- [ ] Security scan green (0 critical, 0 high)
- [ ] Load test passed: 1000 RPS sustained, < 1% error rate
- [ ] Go/no-go decision documented
```

---

## Optional (Post-MVP)

- Introduce authentication (API key/OAuth) for privileged/internal endpoints.
- Add canary or blue/green deployment workflow.
- Add WAF advanced managed rules and bot controls.
- Add automated chaos/failure-injection test suite in CI.
- Add centralized SIEM integration and compliance-oriented audit trails.

## Updated Acceptance Criteria

- ✅ No unresolved critical/high security findings (OWASP top 10 covered)
- ✅ Input validation hardened across all endpoints
- ✅ SQL injection protection verified (no string concatenation)
- ✅ All HTTP security headers configured (CSP, HSTS, X-Frame-Options, etc.)
- ✅ Sensitive data masked in logs and error responses
- ✅ CORS policy restricted to production origins only
- ✅ Load test completed: p95 < 500ms, p99 < 1s at 1000 RPS
- ✅ Error rate < 1% sustained at 500 VU (1000 RPS)
- ✅ Failure scenarios tested (cache down, DB down, Redis unavailable)
- ✅ Deployment checklist completed and signed off
- ✅ Production runbook with incident playbooks documented
- ✅ Health check endpoints (liveness, readiness) verified
- ✅ Configuration management via profile + environment variables (no secrets in code)
- ✅ All documentation synchronized with implementation
- ✅ Go-live sign-off obtained (security, ops, product)
