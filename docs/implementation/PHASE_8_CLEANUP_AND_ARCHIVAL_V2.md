# Phase 8: Cleanup and Archival (v2)

## Objective

Implement scheduled expiration cleanup and archival support without affecting serving traffic.

## MVP-Only Execution Rule

- For the current timeline, implement only content before `## Optional (Post-MVP)`.
- Treat `## Optional (Post-MVP)` items as backlog and do not block release.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 5 Cache Layer (v2)](PHASE_5_CACHE_LAYER_V2.md)

## Source References

- [Use Cases v2](../use-cases/v2/)
- [HLD Database v2 Operations](../hld/database/v2/db-operations.md)
- [HLD Database v2 Durability](../hld/database/v2/db-durability.md)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

## In Scope

- scheduled cleanup job
- soft-delete and grace-period handling
- cache invalidation for cleaned records
- archival policy execution path

## Out of Scope

- full historical analytics platform
- cold storage query APIs

## Execution Steps

### Step 1: Cleanup scheduler baseline

References:

- [Use Case: Expire URL v2](../use-cases/v2/UC-US-003-expire-url.md)

Tasks:

- Add scheduled job with configurable interval
- Define bounded batch size and transaction strategy

### Step 2: Soft-delete lifecycle implementation

References:

- [HLD Database v2 Operations](../hld/database/v2/db-operations.md)

Tasks:

- Mark eligible records as soft-deleted after grace period
- Preserve auditability fields for operations review

### Step 3: Cache invalidation integration

References:

- [LLD Cache Module](../lld/cache-module.md)

Tasks:

- Remove or invalidate affected cache entries after cleanup
- Ensure stale redirects are not served from cache

### Step 4: Archival and safety checks

References:

- [HLD Database v2 Durability](../hld/database/v2/db-durability.md)

Tasks:

- Define archival trigger and retention policy
- Add cleanup metrics and failure alerts

## Deliverables

- periodic cleanup job in place
- soft-delete and cache invalidation behavior validated
- archival path documented and tested for safety

## Acceptance Criteria

- Cleanup does not degrade online request path stability
- Expired records are processed according to grace policy
- Failures are observable and recoverable

---

## Part 2: Implementation Deep Dive

### Job Scheduling Framework Architecture

**Framework Comparison:**

| Framework | Pros | Cons | Recommendation |
| --- | --- | --- | --- |
| **Spring @Scheduled** | Simple, no external dependencies, good for single-instance | No clustering support, basic retry logic | ✅ For v2 baseline (single instance) |
| **Quartz** | Clustering support, persistent job store, complex scheduling | Heavy, overkill for simple jobs | Use if multi-instance needed |
| **Spring Batch** | Large-scale dataset processing, skip/retry logic built-in | Heavyweight framework overhead | Future scaling beyond v2 |

**Recommended: Spring @Scheduled with Redis-based distributed locking** (prevents concurrent execution in scaled scenarios).

**Configuration:**

```yaml
cleanup:
  enabled: true
  schedule:
	cron: "0 2 * * *"  # 2 AM UTC daily (low-traffic window)
  batch-size: 1000     # Process 1000 URLs per batch
  grace-period-days: 1 # 1-day grace period before cleanup
  transaction-timeout-seconds: 120 # 2-minute max transaction time
  archive-threshold-days: 30 # Archive after 30 days of deletion
```

**Spring Boot Scheduler Configuration:**

```java
@Configuration
@EnableScheduling
public class SchedulingConfig {
	@Bean
	public TaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(2);
		scheduler.setThreadNamePrefix("cleanup-scheduler-");
		scheduler.setAwaitTerminationSeconds(60);
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		return scheduler;
	}
}
```

---

### Soft-Delete Lifecycle Implementation

**Database Schema Enhancement:**

```sql
ALTER TABLE urls ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE;
ALTER TABLE urls ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE urls ADD COLUMN deletion_reason VARCHAR(50) NULL;

CREATE INDEX idx_urls_is_deleted ON urls(is_deleted, deleted_at);

CREATE TABLE urls_archive (
	id BIGINT PRIMARY KEY,
	alias VARCHAR(32) NOT NULL,
	custom_alias VARCHAR(32),
	target_url TEXT NOT NULL,
	created_at TIMESTAMP NOT NULL,
	deleted_at TIMESTAMP NOT NULL,
	deletion_reason VARCHAR(50),
	access_count BIGINT DEFAULT 0,
	last_accessed_at TIMESTAMP,
	INDEX idx_archived_deleted_at (deleted_at)
) PARTITION BY RANGE YEAR(deleted_at) (
	PARTITION p_2024 VALUES LESS THAN (2025),
	PARTITION p_2025 VALUES LESS THAN (2026),
	PARTITION p_future VALUES LESS THAN MAXVALUE
);
```

**Soft-Delete Entity:**

```java
@Entity
@Table(name = "urls")
@SQLDelete(sql = "UPDATE urls SET is_deleted = true, deleted_at = NOW(), deletion_reason = ? WHERE id = ?")
@Where(clause = "is_deleted = false")
public class Url {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String alias;

	@Column
	private String customAlias;

	@Column(columnDefinition = "TEXT", nullable = false)
	private String targetUrl;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	@Column
	private LocalDateTime expiryTime;

	@Column(nullable = false)
	private Boolean isDeleted = false;

	@Column
	private LocalDateTime deletedAt;

	@Column
	private String deletionReason;

	@Column
	private Long accessCount = 0L;

	public void softDelete(DeletionReason reason) {
		this.isDeleted = true;
		this.deletedAt = LocalDateTime.now();
		this.deletionReason = reason.name();
	}

	public enum DeletionReason {
		EXPIRED, USER_REQUESTED, ADMIN_DELETION, CLEANUP
	}
}
```

---

### Cleanup Job Implementation

**Main Cleanup Service with Distributed Locking:**

```java
@Service
public class UrlCleanupService {
	private final UrlRepository urlRepository;
	private final CacheService cacheService;
	private final UrlArchiveService archiveService;
	private final MeterRegistry meterRegistry;
	private final RedisTemplate<String, String> redisTemplate;
	private final Logger log = LoggerFactory.getLogger(UrlCleanupService.class);

	@Value("${cleanup.grace-period-days:1}")
	private int gracePeriodDays;

	@Value("${cleanup.batch-size:1000}")
	private int batchSize;

	@Scheduled(cron = "${cleanup.schedule.cron:0 2 * * *}")
	public void scheduleCleanup() {
		if (!acquireCleanupLock()) {
			log.info("Cleanup already running. Skipping this interval.");
			meterRegistry.counter("cleanup_skipped_locked").increment();
			return;
		}

		try {
			log.info("Starting scheduled cleanup job");
			Timer.Sample sample = Timer.start(meterRegistry);

			long cleanupCount = performCleanup();
			long archiveCount = performArchival();

			sample.stop(Timer.builder("cleanup_job_duration")
				.tag("status", "success")
				.register(meterRegistry));

			log.info("Cleanup completed. Cleaned: {}, Archived: {}", cleanupCount, archiveCount);

		} catch (Exception e) {
			log.error("Cleanup job failed", e);
			meterRegistry.counter("cleanup_job_failure").increment();
		} finally {
			releaseCleanupLock();
		}
	}

	private long performCleanup() {
		long totalCleaned = 0;
		int batchCount = 0;

		while (batchCount < 100) {
			List<Url> expiredBatch = urlRepository.findExpiredUrlsForCleanup(
				gracePeriodDays, batchSize
			);

			if (expiredBatch.isEmpty()) break;

			long batchCleaned = cleanupBatch(expiredBatch);
			totalCleaned += batchCleaned;
			batchCount++;

			if (expiredBatch.size() < batchSize) break;
		}

		return totalCleaned;
	}

	@Transactional(timeout = 120)
	private long cleanupBatch(List<Url> urls) {
		long cleanedCount = 0;
		for (Url url : urls) {
			try {
				url.softDelete(Url.DeletionReason.CLEANUP);
				urlRepository.save(url);

				cacheService.invalidate(url.getAlias());
				if (url.getCustomAlias() != null) {
					cacheService.invalidate(url.getCustomAlias());
				}

				cleanedCount++;

			} catch (Exception e) {
				log.error("Error cleaning up URL id={}: {}", url.getId(), e.getMessage());
			}
		}
		return cleanedCount;
	}

	private boolean acquireCleanupLock() {
		try {
			Boolean lockAcquired = redisTemplate.opsForValue()
				.setIfAbsent("cleanup:lock", 
					String.valueOf(System.currentTimeMillis()),
					Duration.ofSeconds(30 * 60));
			return lockAcquired != null && lockAcquired;
		} catch (Exception e) {
			log.warn("Failed to acquire cleanup lock: {}", e.getMessage());
			return true;
		}
	}

	private void releaseCleanupLock() {
		try {
			redisTemplate.delete("cleanup:lock");
		} catch (Exception e) {
			log.warn("Failed to release cleanup lock: {}", e.getMessage());
		}
	}
}
```

---

### Archive Service & Cache Invalidation

**Archive Service:**

```java
@Service
public class UrlArchiveService {
	private final JdbcTemplate jdbcTemplate;
	private final MeterRegistry meterRegistry;

	public long archiveBatch(List<Url> urls) {
		if (urls.isEmpty()) return 0;

		String insertSql = """
			INSERT INTO urls_archive 
			(id, alias, custom_alias, target_url, created_at, deleted_at, deletion_reason, access_count)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			""";

		List<Object[]> batchArgs = urls.stream()
			.map(u -> new Object[]{
				u.getId(), u.getAlias(), u.getCustomAlias(), u.getTargetUrl(),
				u.getCreatedAt(), u.getDeletedAt(), u.getDeletionReason(), u.getAccessCount()
			})
			.collect(Collectors.toList());

		int[] results = jdbcTemplate.batchUpdate(insertSql, batchArgs);
		return Arrays.stream(results).count();
	}
}
```

**Cache Invalidation Service:**

```java
@Service
public class CacheService {
	private final RedisTemplate<String, Url> redisTemplate;
	private final MeterRegistry meterRegistry;

	public void invalidate(String alias) {
		if (alias == null || alias.isEmpty()) return;

		try {
			Boolean deleted = redisTemplate.delete("url:" + alias) > 0;
			if (deleted) {
				meterRegistry.counter("cache_invalidation_success").increment();
			}
		} catch (Exception e) {
			log.warn("Failed to invalidate cache for alias '{}': {}", alias, e.getMessage());
			meterRegistry.counter("cache_invalidation_error").increment();
		}
	}
}
```

---

### Monitoring & Observability

**Prometheus Alerts:**

```yaml
groups:
  - name: cleanup_and_archival
	rules:
	  - alert: CleanupJobFailure
		expr: rate(cleanup_job_failure[1h]) > 0
		for: 5m
		annotations:
		  summary: "URL cleanup job failed"

	  - alert: CleanupJobNotRun
		expr: time() - cleanup_job_last_run > 86400 * 1.5
		for: 10m
		annotations:
		  summary: "Cleanup job has not run in 36 hours"

	  - alert: HighCacheInvalidationErrors
		expr: rate(cache_invalidation_error[5m]) > 0.1
		for: 5m
		annotations:
		  summary: "High cache invalidation error rate"
```

---

## Optional (Post-MVP)

- Move archival to dedicated cold storage pipeline for long-retention analytics.
- Add multi-stage archival tiers and compression policies.
- Add replay/recovery tooling for archived records.
- Add cleanup orchestration via Quartz/Spring Batch if job complexity grows.

## Updated Acceptance Criteria

- ✅ Cleanup job scheduled and runs on configured cron (2 AM UTC)
- ✅ Expired URLs past grace period are soft-deleted deterministically
- ✅ Cache entries invalidated immediately after soft-delete
- ✅ Soft-deleted URLs archived after threshold to archive table
- ✅ Archive table partitioned by year for efficient queries
- ✅ Cleanup does not degrade online request latency
- ✅ Distributed lock prevents concurrent cleanup execution
- ✅ Cleanup failures trigger alerts and are recoverable
