# PROMO ENGINE — Campaign & Notification Platform
> **Portfolio Project** | Java · Spring Boot · Kafka · Redis · API Gateway · JWT

---

## 1. Tổng quan

**PROMO ENGINE** là một platform B2B cho phép doanh nghiệp tạo, lên lịch, và kích hoạt campaign khuyến mãi, đồng thời orchestrate thông báo đa kênh (email, SMS, push) tới người dùng cuối.

### Tại sao build project này?
- Cover đầy đủ các pattern quan trọng: Outbox, Saga, Fan-out, Rate Limiting, Distributed Lock
- Business logic thật, không phải CRUD đơn thuần
- Dễ giải thích trong interview vì gần với thực tế (e-commerce, fintech)
- Tự dùng được: chạy campaign cho các site thật

### High-level Flow
```
ADMIN tạo campaign
    → Campaign được schedule
    → Đúng giờ: Executor kích hoạt → CampaignActivated event lên Kafka
    → Voucher Service: generate voucher pool, mở claim
    → Notification Service: fan-out thông báo tới users
    → User claim voucher (race condition → Redis atomic)
    → Confirmation notification gửi về user
```

---

## 2. Architecture

```
                     ┌───────────────────────────────┐
                     │          API Gateway           │
                     │  JWT Validation · Rate Limit   │
                     │  Routing · Correlation ID      │
                     └──┬────┬────┬────┬────┬────┬───┘
                        │    │    │    │    │    │
              ┌─────────┘    │    │    │    │    └──────────┐
              ▼              ▼    │    ▼    ▼               ▼
        [Auth Svc]    [Campaign   │  [Voucher]   [Notification]
                         Svc]    │    Svc]          Svc]
              │              │    │    │               │
              └──────────────┴─Kafka───┴───────────────┘
                                  │
                           [Executor Svc]
                                  │
                    ┌─────────────┼─────────────┐
                    ▼             ▼             ▼
                  Redis        MariaDB       Kafka
```

### Tech Stack
| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.x |
| Gateway | Spring Cloud Gateway |
| Security | Spring Security + JWT (RS256) |
| Messaging | Apache Kafka |
| Cache | Redis (Redisson + Caffeine L1) |
| Database | MariaDB |
| Distributed Lock | Redisson RLock |
| Resilience | Resilience4j (Circuit Breaker) |
| Testing | JUnit 5 + Mockito + Testcontainers |
| Infra | Docker Compose |
| Docs | Swagger / OpenAPI 3 |

---

## 3. Services Chi Tiết

---

### 3.1 Auth Service

**Port:** `8081`  
**Responsibility:** Quản lý identity, phát hành và revoke token.

#### Roles
| Role | Quyền |
|---|---|
| `ADMIN` | Full access: tạo/sửa/xóa campaign, xem analytics, quản lý user |
| `MARKETER` | Tạo/sửa campaign, không xóa, xem report của campaign mình |
| `USER` | Claim voucher, xem notification của bản thân |

#### APIs
```
POST   /auth/register          → Đăng ký tài khoản
POST   /auth/login             → Login, trả về access_token + refresh_token
POST   /auth/refresh           → Đổi refresh_token lấy access_token mới
POST   /auth/logout            → Revoke refresh_token hiện tại
POST   /auth/logout-all        → Revoke tất cả session của user
GET    /auth/me                → Thông tin user hiện tại
```

#### Key Design Decisions

**JWT RS256 thay vì HS256:**
- RS256: private key ký (Auth Service), public key verify (Gateway + các service khác)
- Lợi thế: các service khác verify token mà không cần gọi về Auth Service → giảm latency, không có single point of failure
- HS256: shared secret → nếu 1 service bị compromise, toàn bộ hệ thống bị ảnh hưởng

**Refresh Token Rotation + Reuse Detection:**
```
Flow bình thường:
  refresh_token_v1 → [/auth/refresh] → access_token_new + refresh_token_v2
  refresh_token_v1 bị invalidate ngay lập tức

Attack scenario:
  Attacker dùng refresh_token_v1 sau khi đã rotate → hệ thống detect reuse
  → Revoke TOÀN BỘ session của user đó
  → User phải login lại

Implementation:
  - Store refresh token trong Redis: key = "rt:{userId}:{tokenId}", TTL = 7 ngày
  - Khi rotate: xóa token cũ, tạo token mới
  - Khi detect reuse (token cũ không tồn tại trong Redis nhưng signature hợp lệ):
    → SCAN và xóa toàn bộ "rt:{userId}:*"
```

**Token Structure:**
```json
{
  "sub": "user-uuid",
  "roles": ["MARKETER"],
  "iat": 1714000000,
  "exp": 1714000900
}
```
Access token TTL: **15 phút**  
Refresh token TTL: **7 ngày**

#### Database Schema
```sql
CREATE TABLE users (
    id          VARCHAR(36) PRIMARY KEY,
    email       VARCHAR(255) UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,        -- BCrypt
    role        ENUM('ADMIN','MARKETER','USER') NOT NULL,
    status      ENUM('ACTIVE','LOCKED') DEFAULT 'ACTIVE',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

---

### 3.2 API Gateway

**Port:** `8080`  
**Technology:** Spring Cloud Gateway  
**Responsibility:** Single entry point — auth validation, rate limiting, routing.

#### Routing Table
| Path Prefix | Route đến | Notes |
|---|---|---|
| `/auth/**` | Auth Service | Public, không validate JWT |
| `/campaigns/**` | Campaign Service | JWT required |
| `/vouchers/**` | Voucher Service | JWT required |
| `/notifications/**` | Notification Service | JWT required |

#### JWT Validation Filter
```
Request đến Gateway
  → Extract Bearer token từ Authorization header
  → Verify signature bằng Auth Service public key (cached tại Gateway)
  → Decode claims (userId, roles)
  → Forward request với headers: X-User-Id, X-User-Role, X-Correlation-Id
  → Downstream services tin tưởng headers này, không verify lại JWT
```

**Public key caching:** Fetch từ Auth Service endpoint `GET /auth/.well-known/jwks.json`, cache trong memory 1 giờ. Tránh gọi Auth Service mỗi request.

#### Rate Limiting — Token Bucket (Redis Lua)

**Per-endpoint limits:**
| Endpoint | Limit | Lý do |
|---|---|---|
| `POST /vouchers/claim` | 1 req/giây/user | Chống spam claim |
| `POST /auth/login` | 5 req/phút/IP | Chống brute force |
| `GET /campaigns/**` | 100 req/phút/user | Read, thoải mái |
| Default | 60 req/phút/user | General protection |

**Lua script (atomic):**
```lua
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now

-- Tính tokens được refill từ lần check trước
local elapsed = now - last_refill
local refill = math.floor(elapsed * refill_rate)
tokens = math.min(capacity, tokens + refill)

if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    redis.call('EXPIRE', key, 3600)
    return 1  -- allowed
end
return 0  -- rate limited
```

#### Correlation ID
Gateway inject `X-Correlation-Id` (UUID) vào mọi request. Tất cả services log với correlation ID này → trace request across services dễ dàng.

---

### 3.3 Campaign Service

**Port:** `8082`  
**Responsibility:** CRUD campaign, quản lý lifecycle, publish events.

#### Campaign State Machine
```
DRAFT ──[schedule]──► SCHEDULED ──[activation time reached]──► ACTIVE
  │                       │                                       │
  └──[delete]──► DELETED  └──[cancel]──► CANCELLED    [end time]─┘
                                                                   ▼
                                                                 ENDED
```

**State transition rules:**
- Chỉ `DRAFT` và `SCHEDULED` mới được sửa
- Chỉ `SCHEDULED` mới được cancel
- `ACTIVE` không thể revert về bất kỳ state nào
- Executor Service là **duy nhất** được trigger transition `SCHEDULED → ACTIVE` và `ACTIVE → ENDED`

#### APIs
```
POST   /campaigns                    → Tạo campaign mới (DRAFT)
GET    /campaigns/{id}               → Xem chi tiết
GET    /campaigns?status=&page=      → List với filter
PUT    /campaigns/{id}               → Sửa (chỉ DRAFT/SCHEDULED)
POST   /campaigns/{id}/schedule      → DRAFT → SCHEDULED
POST   /campaigns/{id}/cancel        → SCHEDULED → CANCELLED
GET    /campaigns/{id}/analytics     → Xem stats (voucher claimed, notification sent)
```

#### Campaign Entity
```sql
CREATE TABLE campaigns (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    start_time      DATETIME NOT NULL,
    end_time        DATETIME NOT NULL,
    status          ENUM('DRAFT','SCHEDULED','ACTIVE','ENDED','CANCELLED') DEFAULT 'DRAFT',
    voucher_quota   INT NOT NULL,               -- tổng số voucher
    voucher_value   DECIMAL(15,2) NOT NULL,     -- giá trị voucher
    voucher_type    ENUM('PERCENTAGE','FIXED'),  -- % hoặc số tiền cố định
    target_segment  JSON,                       -- {"minAge": 18, "city": "HN"}
    created_by      VARCHAR(36) NOT NULL,
    version         INT DEFAULT 0,              -- optimistic locking
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME ON UPDATE CURRENT_TIMESTAMP
);
```

**`version` field — Optimistic Locking:**
```java
// Tránh lost update khi 2 MARKETER cùng sửa 1 campaign
UPDATE campaigns
SET status = 'SCHEDULED', version = version + 1
WHERE id = ? AND version = ?   -- nếu version lệch → throw OptimisticLockException
```

#### Outbox Pattern — Tại sao cần thiết?

**Vấn đề không có Outbox:**
```
1. UPDATE campaign status = ACTIVE  ✓
2. kafka.send(CampaignActivated)     ✗ ← crash ở đây
→ DB updated nhưng Kafka không có event → Voucher/Notification không được trigger
```

**Với Outbox Pattern:**
```
Transaction {
    1. UPDATE campaign status = ACTIVE
    2. INSERT INTO outbox (event_type, payload, status = PENDING)
}  ← commit atomically

Separate Outbox Poller (chạy mỗi 5 giây):
    SELECT * FROM outbox WHERE status = 'PENDING'
    → kafka.send(event)
    → UPDATE outbox SET status = 'PUBLISHED'
```

```sql
CREATE TABLE outbox_events (
    id           VARCHAR(36) PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,   -- 'CampaignActivated', 'CampaignEnded'
    aggregate_id VARCHAR(36) NOT NULL,    -- campaignId
    payload      JSON NOT NULL,
    status       ENUM('PENDING','PUBLISHED','FAILED') DEFAULT 'PENDING',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME
);
```

#### Kafka Events Published
```json
// Topic: campaign.events
{
  "eventType": "CampaignActivated",
  "campaignId": "uuid",
  "campaignName": "Summer Sale 2024",
  "voucherQuota": 1000,
  "voucherValue": 50000,
  "voucherType": "FIXED",
  "targetSegment": {"city": "HN"},
  "occurredAt": "2024-06-01T10:00:00Z"
}
```

---

### 3.4 Voucher Service

**Port:** `8083`  
**Responsibility:** Generate voucher pool, xử lý claim với race condition safety.

#### Flow khi nhận CampaignActivated
```
1. Consume event từ Kafka (topic: campaign.events)
2. Generate N voucher codes (N = voucherQuota)
3. Bulk insert vào DB
4. SET Redis key: "voucher:quota:{campaignId}" = N
5. SET Redis key: "voucher:active:{campaignId}" = 1 (flag campaign đang active)
```

#### Claim Flow — Race Condition Handling

**Vấn đề:** 10,000 user cùng POST `/vouchers/claim/{campaignId}` trong 1 giây.

**Solution — Redis Lua script atomic:**
```lua
-- KEYS[1]: "voucher:quota:{campaignId}"
-- KEYS[2]: "voucher:claimed:{campaignId}:{userId}"
-- ARGV[1]: userId

-- Check user đã claim chưa
local already = redis.call('GET', KEYS[2])
if already then
    return -1  -- already claimed
end

-- Check còn voucher không
local remaining = tonumber(redis.call('GET', KEYS[1]))
if not remaining or remaining <= 0 then
    return 0   -- sold out
end

-- Atomic claim
redis.call('DECR', KEYS[1])
redis.call('SET', KEYS[2], ARGV[1], 'EX', 86400)
return 1  -- success
```

**Sau khi Redis confirm claimed:**
```
→ Lấy 1 voucher code từ pool (status = AVAILABLE)
→ UPDATE vouchers SET status = 'CLAIMED', claimed_by = userId WHERE ...
→ INSERT INTO outbox (VoucherClaimed event)
→ Return voucher code cho user
```

#### APIs
```
POST   /vouchers/claim/{campaignId}      → Claim voucher
GET    /vouchers/my                      → Xem vouchers của mình
GET    /vouchers/{code}/validate         → Validate voucher (cho merchant)
POST   /vouchers/{code}/redeem           → Mark voucher as used
GET    /vouchers/campaign/{id}/stats     → Xem remaining quota (ADMIN)
```

#### Database Schema
```sql
CREATE TABLE vouchers (
    id           VARCHAR(36) PRIMARY KEY,
    code         VARCHAR(50) UNIQUE NOT NULL,
    campaign_id  VARCHAR(36) NOT NULL,
    status       ENUM('AVAILABLE','CLAIMED','USED','EXPIRED') DEFAULT 'AVAILABLE',
    claimed_by   VARCHAR(36),
    claimed_at   DATETIME,
    expires_at   DATETIME NOT NULL,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_campaign_status (campaign_id, status),
    INDEX idx_claimed_by (claimed_by)
);
```

#### Idempotency
Mỗi claim request kèm `Idempotency-Key` header. Gateway/Service check Redis trước khi xử lý → tránh duplicate claim do retry của client.

---

### 3.5 Notification Service

**Port:** `8084`  
**Responsibility:** Fan-out thông báo đa kênh dựa trên Kafka events.

#### Events Consumed & Actions

| Event | Topic | Action |
|---|---|---|
| `CampaignActivated` | `campaign.events` | Gửi push/email cho target segment |
| `VoucherClaimed` | `voucher.events` | Gửi confirmation cho user |
| `VoucherExpiringSoon` | `voucher.events` | Reminder 24h trước khi hết hạn |
| `CampaignEnded` | `campaign.events` | Summary report cho ADMIN/MARKETER |

#### Fan-out Architecture
```
CampaignActivated event
    │
    ├──► EmailNotificationHandler
    │       → Fetch users thuộc target segment (theo batch 500)
    │       → Render template
    │       → Send (mock SMTP / Mailhog local)
    │
    ├──► PushNotificationHandler
    │       → Fetch device tokens của users
    │       → Send push (mock FCM)
    │
    └──► SMSNotificationHandler
            → Chỉ gửi cho users có phone, value > 100K
            → Rate limit: 10 SMS/giây (chống spam + cost control)
```

#### Template Engine
```
Template: "Xin chào {{userName}}, campaign {{campaignName}} vừa kích hoạt!
           Còn {{remainingVouchers}} voucher trị giá {{voucherValue}}đ.
           Claim ngay tại: {{claimUrl}}"

Variables được resolve từ event payload + user profile lookup.
```

#### Rate Limiting per Channel
```java
// Mỗi channel có rate limiter riêng trong Redis
RateLimiter emailLimiter = // 100 emails/giây
RateLimiter smsLimiter   = // 10 SMS/giây
RateLimiter pushLimiter  = // 500 push/giây
```

#### Retry + Dead Letter Queue
```
Kafka Topic: notification.tasks
    → Xử lý thất bại
    → notification.retry.1 (delay 1 phút)
    → notification.retry.2 (delay 5 phút)
    → notification.retry.3 (delay 30 phút)
    → notification.dlq → Alert → Manual review
```

#### Database Schema
```sql
CREATE TABLE notification_logs (
    id              VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(36) NOT NULL,
    channel         ENUM('EMAIL','SMS','PUSH'),
    event_type      VARCHAR(100),
    template_id     VARCHAR(36),
    status          ENUM('SENT','FAILED','SKIPPED'),
    error_message   TEXT,
    sent_at         DATETIME,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_status_created (status, created_at)
);
```

---

### 3.6 Executor Service

**Port:** `8085` (internal only, không expose qua Gateway)  
**Responsibility:** Cron-based scheduler — kích hoạt và kết thúc campaign đúng giờ.

#### Scheduling Logic
```
Mỗi 1 phút, Executor chạy 2 jobs:

Job 1 — Activation Scanner:
    SELECT id FROM campaigns
    WHERE status = 'SCHEDULED'
    AND start_time <= NOW()
    FOR UPDATE SKIP LOCKED     ← tránh multiple pods cùng xử lý

    Với mỗi campaign:
    → UPDATE status = 'ACTIVE'
    → INSERT INTO outbox (CampaignActivated)

Job 2 — Ending Scanner:
    SELECT id FROM campaigns
    WHERE status = 'ACTIVE'
    AND end_time <= NOW()
    FOR UPDATE SKIP LOCKED

    Với mỗi campaign:
    → UPDATE status = 'ENDED'
    → INSERT INTO outbox (CampaignEnded)
```

#### Distributed Lock — Exactly Once Execution
```java
RLock lock = redissonClient.getLock("executor:activation-scan");
boolean acquired = lock.tryLock(0, 50, TimeUnit.SECONDS);

if (acquired) {
    try {
        runActivationScan();
    } finally {
        lock.unlock();
    }
}
// Nếu không acquire được lock → skip, pod khác đang chạy
```

**Tại sao dùng `tryLock(0, ...)` thay vì `lock()`?**
- `lock()` sẽ block và chờ → nếu previous execution chưa xong, task mới chờ → accumulate
- `tryLock(0, ...)` → nếu không lấy được lock ngay → skip cycle này → safer

#### Outbox Poller (trong Executor Service)
```
Mỗi 5 giây:
    SELECT * FROM outbox_events
    WHERE status = 'PENDING'
    ORDER BY created_at ASC
    LIMIT 100
    FOR UPDATE SKIP LOCKED

    → kafka.send(event)
    → UPDATE status = 'PUBLISHED'
    → Nếu Kafka fail: UPDATE status = 'FAILED', retry_count++
```

---

## 4. Cross-cutting Concerns

### 4.1 Observability
```
Mỗi service expose:
    GET /actuator/health    → Health check
    GET /actuator/metrics   → Micrometer metrics

Metrics quan trọng cần track:
    - voucher.claim.success / voucher.claim.failed / voucher.claim.sold_out
    - notification.sent.{channel} / notification.failed.{channel}
    - campaign.activated / campaign.ended
    - kafka.consumer.lag.{topic}
    - redis.rate_limit.rejected
```

### 4.2 Structured Logging
```json
{
  "timestamp": "2024-06-01T10:00:00Z",
  "level": "INFO",
  "service": "voucher-service",
  "correlationId": "abc-123",        ← từ Gateway header
  "userId": "user-uuid",
  "campaignId": "campaign-uuid",
  "event": "VOUCHER_CLAIMED",
  "message": "Voucher claimed successfully"
}
```

### 4.3 Error Handling
```
Business errors (4xx):
    → Return error code + message, không throw exception lên Kafka retry

Technical errors (5xx, timeout):
    → Retry theo DLQ flow
    → Log với full stack trace + correlationId

Circuit Breaker (Resilience4j):
    → Wrap external calls (mock payment gateway, mock FCM)
    → Threshold: 50% failure rate trong 10 giây → OPEN
    → Half-open sau 30 giây để test recovery
```

---

## 5. Kafka Topics

| Topic | Producer | Consumer | Retention |
|---|---|---|---|
| `campaign.events` | Campaign Svc (via Outbox) | Voucher Svc, Notification Svc, Executor Svc | 7 ngày |
| `voucher.events` | Voucher Svc (via Outbox) | Notification Svc | 7 ngày |
| `notification.tasks` | Notification Svc | Notification Svc | 3 ngày |
| `notification.retry.1` | Notification Svc | Notification Svc | 1 ngày |
| `notification.retry.2` | Notification Svc | Notification Svc | 1 ngày |
| `notification.dlq` | Notification Svc | Manual / Alert | 30 ngày |

**Partition strategy:**
- `campaign.events`: partition by `campaignId` → ordering per campaign
- `voucher.events`: partition by `userId` → ordering per user
- `notification.tasks`: partition by `userId` → same user nhận notification theo thứ tự

---

## 6. Redis Key Design

| Key Pattern | Type | TTL | Dùng cho |
|---|---|---|---|
| `rt:{userId}:{tokenId}` | String | 7 ngày | Refresh token |
| `jwks:public_key` | String | 1 giờ | Cached public key tại Gateway |
| `rl:{userId}:{endpoint}` | Hash | 1 giờ | Rate limit token bucket |
| `voucher:quota:{campaignId}` | String | Campaign duration | Remaining vouchers |
| `voucher:active:{campaignId}` | String | Campaign duration | Campaign active flag |
| `voucher:claimed:{campaignId}:{userId}` | String | 1 ngày | Claimed check |
| `idempotency:{key}` | String | 24 giờ | Idempotency check |
| `executor:activation-scan` | Lock | 50 giây | Distributed lock |
| `notif:rl:{channel}` | Hash | 1 giờ | Notification rate limit |

---

## 7. Docker Compose Setup

```yaml
# docker-compose.yml (tóm tắt)
services:
  mariadb:
    image: mariadb:10.11
    ports: ["3306:3306"]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports: ["9092:9092"]

  kafka-ui:
    image: provectuslabs/kafka-ui    # UI để xem topics/messages
    ports: ["8090:8080"]

  mailhog:
    image: mailhog/mailhog           # Mock SMTP server
    ports: ["1025:1025", "8025:8025"]

  auth-service:      { build: ./auth-service,         ports: ["8081:8081"] }
  api-gateway:       { build: ./api-gateway,           ports: ["8080:8080"] }
  campaign-service:  { build: ./campaign-service,      ports: ["8082:8082"] }
  voucher-service:   { build: ./voucher-service,       ports: ["8083:8083"] }
  notification-svc:  { build: ./notification-service,  ports: ["8084:8084"] }
  executor-service:  { build: ./executor-service,      ports: ["8085:8085"] }
```

Chạy toàn bộ stack: `docker-compose up -d`

---

## 8. Build Order & Milestones

### Week 1–2: Auth Service
- [ ] User entity, BCrypt password
- [ ] JWT RS256: generate key pair, sign/verify
- [ ] Login/Register APIs
- [ ] Refresh token rotation + reuse detection (Redis)
- [ ] Logout / logout-all
- [ ] Unit test: TokenService, AuthService (Mockito)

### Week 3: API Gateway
- [ ] Spring Cloud Gateway setup
- [ ] JWT validation filter (verify RS256 bằng public key)
- [ ] Public key caching từ Auth Service
- [ ] Rate limiting filter (Token Bucket + Redis Lua)
- [ ] Correlation ID injection
- [ ] Route configuration
- [ ] Integration test: filter chain

### Week 4–5: Campaign Service
- [ ] Campaign entity + state machine
- [ ] CRUD APIs
- [ ] Schedule/Cancel endpoints
- [ ] Optimistic locking trên state transition
- [ ] Outbox table + Outbox Poller
- [ ] Kafka producer (via Outbox)
- [ ] Unit test: state machine transitions
- [ ] Integration test: Outbox → Kafka (Testcontainers)

### Week 6: Voucher Service
- [ ] Voucher generation khi nhận CampaignActivated
- [ ] Redis quota setup
- [ ] Claim endpoint + Redis Lua script
- [ ] Idempotency check
- [ ] Voucher validate + redeem
- [ ] Unit test: claim logic
- [ ] Integration test: race condition (concurrent requests)

### Week 7: Notification Service
- [ ] Kafka consumers cho từng event type
- [ ] Template engine (simple string interpolation)
- [ ] Email handler (Mailhog)
- [ ] Push handler (mock)
- [ ] SMS handler (mock, với rate limit)
- [ ] Retry topics + DLQ
- [ ] Circuit Breaker (Resilience4j) cho external calls
- [ ] Integration test: fan-out flow

### Week 8: Executor Service
- [ ] Cron jobs (activation + ending scanner)
- [ ] `SELECT FOR UPDATE SKIP LOCKED`
- [ ] Redisson distributed lock
- [ ] Outbox Poller
- [ ] Integration test: concurrent pods simulation

### Week 9: Polish
- [ ] Docker Compose hoàn chỉnh (1 lệnh chạy hết)
- [ ] Swagger/OpenAPI cho tất cả services
- [ ] README với architecture diagram (Mermaid)
- [ ] Structured logging + correlationId
- [ ] Metrics endpoints
- [ ] Review và fill unit test coverage lên ~70%

---

## 9. Interview Talking Points

Những câu sẽ được hỏi và mày cần trả lời được:

**Về Outbox Pattern:**
> "Tại sao không gọi Kafka trực tiếp trong transaction?"
> → Kafka không tham gia vào DB transaction. Nếu crash sau DB commit nhưng trước Kafka send → event mất. Outbox đảm bảo atomicity.

**Về Redis Lua trong Voucher claim:**
> "Tại sao dùng Lua script thay vì DECR thông thường?"
> → Cần atomic check + decrement + mark user trong 1 operation. Nếu tách ra làm 2 lệnh riêng thì có race condition giữa check và decrement.

**Về JWT RS256:**
> "Tại sao RS256 thay vì HS256?"
> → RS256 dùng asymmetric key. Chỉ Auth Service có private key để ký. Các service khác chỉ cần public key để verify mà không cần biết secret. Safer trong microservices environment.

**Về Refresh Token Reuse Detection:**
> "Nếu refresh token bị stolen thì sao?"
> → Khi attacker dùng token đã rotate, hệ thống detect reuse và revoke toàn bộ session. User bị force logout nhưng attacker cũng mất access.

**Về Rate Limiting tại Gateway:**
> "Tại sao implement ở Gateway thay vì từng service?"
> → Centralize để không duplicate logic. Gateway là chokepoint duy nhất, enforce nhất quán. Service-level rate limit vẫn có thể thêm như defense-in-depth.

**Về `SELECT FOR UPDATE SKIP LOCKED`:**
> "SKIP LOCKED là gì, tại sao dùng thay vì SKIP?"
> → SKIP LOCKED bỏ qua rows đang bị lock bởi transaction khác thay vì chờ. Trong batch processing multi-pod, tránh deadlock và cho phép parallel processing trên các rows khác nhau.

---

## 10. GitHub Repository Structure

```
promo-engine/
├── docker-compose.yml
├── README.md                          ← Architecture diagram + how to run
├── docs/
│   ├── architecture.md
│   └── api-contracts/
├── auth-service/
│   ├── src/main/java/...
│   ├── src/test/java/...
│   └── Dockerfile
├── api-gateway/
├── campaign-service/
├── voucher-service/
├── notification-service/
└── executor-service/
```

---

## 11. Concurrency Design — Chi Tiết

Hệ thống có 3 điểm concurrency cao, mỗi điểm có cơ chế xử lý riêng biệt.

---

### 11.1 Voucher Claim — 10K users/giây

**Vấn đề:**
```
T=0: User A đọc quota = 1
T=0: User B đọc quota = 1   ← cùng lúc
T=1: User A claim → quota = 0
T=1: User B claim → quota = -1  ← OVERSELL
```

**Solution: Redis Lua Script — Atomic Multi-step Operation**

Lua script chạy single-threaded trong Redis, không có interleaving giữa các lệnh:

```lua
-- KEYS[1] = "voucher:quota:{campaignId}"
-- KEYS[2] = "voucher:claimed:{campaignId}:{userId}"
-- KEYS[3] = "voucher:active:{campaignId}"
-- ARGV[1] = userId, ARGV[2] = TTL seconds

-- Bước 1: Check campaign còn active không
local active = redis.call('GET', KEYS[3])
if not active then
    return {0, 'CAMPAIGN_NOT_ACTIVE'}
end

-- Bước 2: Check user đã claim chưa (per-user dedup)
local already = redis.call('GET', KEYS[2])
if already then
    return {0, 'ALREADY_CLAIMED'}
end

-- Bước 3: Check và decrement quota atomically
local remaining = tonumber(redis.call('GET', KEYS[1]))
if not remaining or remaining <= 0 then
    return {0, 'SOLD_OUT'}
end

redis.call('DECR', KEYS[1])
redis.call('SET', KEYS[2], ARGV[1], 'EX', tonumber(ARGV[2]))
return {1, 'SUCCESS'}
```

**3 check trong 1 atomic operation** — không thể có race condition ở bất kỳ bước nào.

**Sau khi Redis trả về SUCCESS:**
```
→ Pick 1 voucher code từ DB (status = AVAILABLE)
   Query: SELECT id FROM vouchers
          WHERE campaign_id = ? AND status = 'AVAILABLE'
          LIMIT 1
          FOR UPDATE SKIP LOCKED   ← tránh 2 threads pick cùng 1 code

→ UPDATE vouchers SET status = 'CLAIMED', claimed_by = ?, claimed_at = NOW()
→ INSERT INTO outbox (VoucherClaimed event)
→ Return voucher code
```

**Tại sao cần `FOR UPDATE SKIP LOCKED` dù đã có Redis check?**

Redis và DB không share transaction. Giữa thời điểm Redis DECR thành công và DB UPDATE có một khoảng thời gian nhỏ. Với 10K concurrent requests, nhiều threads có thể pass Redis check gần như cùng lúc. `SKIP LOCKED` đảm bảo không có 2 threads nào update cùng 1 voucher row.

**Throughput calculation:**
```
Redis Lua: ~100K ops/giây (single-threaded, in-memory)
MariaDB với connection pool 50: ~5K writes/giây
→ Bottleneck là DB, không phải Redis
→ Giải pháp nếu cần scale hơn: batch claim DB writes (group by 100ms window)
```

---

### 11.2 Notification Fan-out — Triệu users

**Vấn đề:** Khi campaign activate, cần gửi notification cho 500K–1M users. Không thể xử lý trong 1 consumer instance.

**Solution: Partition-based Parallel Processing**

```
CampaignActivated event arrive tại Notification Service
    │
    ▼
[Fanout Coordinator] — chạy 1 lần per event (idempotency check trước)
    │
    ├── Fetch users theo target segment (batch 1000)
    │   SELECT id, email, phone, push_token
    │   FROM users
    │   WHERE ... (segment conditions)
    │   ORDER BY id          ← deterministic ordering
    │   LIMIT 1000 OFFSET ?
    │
    └── Với mỗi batch: publish N messages lên Kafka
        Topic: notification.tasks
        Key: userId           ← partition by userId
```

```
notification.tasks (20 partitions)
    │
    ├── Partition 0  → Consumer Thread 1  → EmailHandler
    ├── Partition 1  → Consumer Thread 2  → EmailHandler
    ├── ...
    └── Partition 19 → Consumer Thread 20 → EmailHandler
```

**Rate limiting per channel (Token Bucket):**
```java
// Mỗi channel handler có rate limiter riêng
// Không dùng Redis cho internal rate limit (too much overhead)
// Dùng Guava RateLimiter (in-process, per pod)

RateLimiter emailLimiter = RateLimiter.create(100.0);  // 100/giây/pod
RateLimiter smsLimiter   = RateLimiter.create(10.0);   // 10/giây/pod
RateLimiter pushLimiter  = RateLimiter.create(500.0);  // 500/giây/pod

// Trước khi gửi:
emailLimiter.acquire();  // blocking, tự throttle
externalEmailClient.send(notification);
```

**Back-pressure handling:**
```
Nếu external provider (FCM, SMTP) chậm → consumer lag tăng
→ Kafka consumer group lag metric alert (threshold: 10K messages)
→ Scale horizontal: thêm consumer pods (tối đa = số partitions)
→ Circuit Breaker open nếu provider error rate > 50% → stop gửi, chờ recovery
```

**Thời gian xử lý 1M notifications:**
```
20 consumer threads × 100 email/giây = 2000 emails/giây
1,000,000 / 2000 = ~500 giây (~8.5 phút)

Chấp nhận được cho marketing notification.
Nếu cần nhanh hơn: tăng partition + consumer pods.
```

---

### 11.3 Campaign Activation — Multi-pod Race

**Vấn đề:** 3 Executor pods cùng chạy cron scan lúc 10:00:00.000 → cùng detect campaign A cần activate → publish 3 `CampaignActivated` events → 3 voucher pools được tạo.

**Solution: 2 lớp bảo vệ**

**Lớp 1 — Redisson RLock (prevent duplicate scan):**
```java
RLock lock = redissonClient.getLock("executor:activation-scan");

// tryLock(waitTime=0) → không chờ, skip nếu lock đang bị giữ
if (lock.tryLock(0, 55, TimeUnit.SECONDS)) {
    try {
        runActivationScan();
    } finally {
        lock.unlock();
    }
} else {
    log.info("Scan skipped — another pod is running");
}
```

leaseTime = 55 giây (< cron interval 60 giây) → tự release nếu pod crash trước khi unlock.

**Lớp 2 — Optimistic Locking trong DB (defense-in-depth):**
```sql
-- Chỉ update nếu status vẫn là SCHEDULED (chưa bị pod khác update)
UPDATE campaigns
SET status = 'ACTIVE',
    version = version + 1,
    updated_at = NOW()
WHERE id = ?
  AND status = 'SCHEDULED'
  AND version = ?
```

```java
int rowsUpdated = campaignRepository.activateCampaign(id, currentVersion);
if (rowsUpdated == 0) {
    // Campaign đã được activate bởi pod khác → skip, không publish event
    log.warn("Campaign {} already activated by another pod", id);
    return;
}
// rowsUpdated == 1 → mình là pod thắng → publish event
outboxRepository.insert(CampaignActivated event);
```

**Tại sao cần 2 lớp?**
- RLock: ngăn duplicate scan ở 99.9% cases → performance tốt
- Optimistic Lock: safety net cho edge cases (lock expire, network partition, clock skew)
- Thiếu 1 trong 2 đều có thể dẫn đến duplicate events

---

## 12. Idempotency Design — Chi Tiết

Idempotency cần cover 3 tầng: API, Kafka Consumer, và Outbox Poller.

---

### 12.1 API Idempotency — Client Retry Safety

**Vấn đề:** Client gửi `POST /vouchers/claim`, nhận timeout (network issue), retry → server nhận 2 requests → user bị claim 2 lần (nếu không có idempotency).

**Solution: Idempotency Key Header**

```
Client gửi:
POST /vouchers/claim/{campaignId}
Idempotency-Key: <client-generated UUID>   ← client tạo 1 lần, giữ nguyên khi retry
```

**Gateway filter — check trước khi forward:**
```java
// Với các mutating endpoints (POST, PUT, DELETE):
String idempotencyKey = request.getHeader("Idempotency-Key");
if (idempotencyKey == null) {
    // Không bắt buộc — nếu thiếu, forward bình thường (no guarantee)
    return chain.filter(exchange);
}

String cacheKey = "idempotency:" + userId + ":" + idempotencyKey;
String cachedResponse = redis.get(cacheKey);

if (cachedResponse != null) {
    // Đã xử lý rồi → trả về cached response ngay, không forward
    return writeResponse(exchange, cachedResponse);
}

// Chưa có → forward, sau đó cache response
return chain.filter(exchange)
    .doOnSuccess(v -> {
        String responseBody = getResponseBody(exchange);
        redis.setex(cacheKey, 86400, responseBody);  // TTL 24h
    });
```

**Idempotency key scope:** `userId + idempotencyKey` — tránh user A dùng key của user B.

**Endpoints áp dụng:**
```
POST /vouchers/claim/{campaignId}    ← critical, must have
POST /campaigns                      ← prevent duplicate campaign creation
POST /campaigns/{id}/schedule        ← prevent double-schedule
```

**Endpoints KHÔNG cần idempotency key:**
```
GET  /**           ← read-only, idempotent by nature
PUT  /campaigns    ← PUT là idempotent by HTTP spec (same result nếu gửi nhiều lần)
DELETE /**         ← idempotent by HTTP spec
```

---

### 12.2 Kafka Consumer Idempotency — At-least-once Delivery

**Vấn đề:** Kafka đảm bảo at-least-once delivery. Consumer xử lý message thành công nhưng crash trước khi commit offset → message được redelivered → duplicate processing.

**Scenario thực tế:**
```
T=0: Consumer nhận CampaignActivated (campaignId=123)
T=1: Voucher pool được tạo thành công (1000 vouchers inserted)
T=2: Consumer CRASH trước khi commit offset
T=3: Consumer restart → nhận lại CampaignActivated (campaignId=123)
T=4: Voucher pool bị tạo lần 2 → 2000 vouchers → oversell
```

**Solution: Event Deduplication Table**

```sql
CREATE TABLE processed_events (
    event_id     VARCHAR(36) PRIMARY KEY,   -- Kafka message key hoặc event UUID
    consumer     VARCHAR(100) NOT NULL,      -- "voucher-service:campaign-activated"
    processed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_consumer (consumer)
);
```

**Consumer logic:**
```java
@KafkaListener(topics = "campaign.events")
public void handleCampaignActivated(CampaignActivatedEvent event) {
    String eventId = event.getEventId();
    String consumer = "voucher-service:campaign-activated";

    // Check trong cùng transaction với business logic
    try {
        transactionTemplate.execute(status -> {
            // Idempotency check
            if (processedEventRepo.existsById(eventId + ":" + consumer)) {
                log.info("Duplicate event {}, skipping", eventId);
                return null;
            }

            // Business logic
            voucherService.generateVoucherPool(event);

            // Mark as processed (cùng transaction)
            processedEventRepo.save(new ProcessedEvent(eventId, consumer));
            return null;
        });
    } catch (DuplicateKeyException e) {
        // Race condition: 2 consumer threads xử lý cùng event
        // INSERT conflict → safe to ignore
        log.warn("Concurrent duplicate event {}, ignored", eventId);
    }
}
```

**Tại sao INSERT vào DB thay vì Redis?**
- Redis check và business logic không share transaction
- Nếu Redis SET thành công nhưng DB rollback → event bị mark processed nhưng thật ra chưa xử lý
- DB INSERT trong cùng transaction với business logic → atomic

**Event ID strategy:**
```
Kafka message key = eventId từ Outbox (UUID được tạo khi insert Outbox)
→ Deterministic, không thay đổi khi redeliver
→ Consumer dùng key này làm dedup key
```

**Cleanup:** Cron job xóa processed_events cũ hơn 7 ngày (= Kafka retention).

---

### 12.3 Outbox Poller Idempotency — Prevent Duplicate Publish

**Vấn đề:** Outbox Poller fetch PENDING events, publish lên Kafka, nhưng crash sau khi publish và trước khi UPDATE status = PUBLISHED → event được publish lại lần sau.

```
T=0: Poller fetch event (status = PENDING)
T=1: kafka.send(event) ✓
T=2: Poller CRASH
T=3: Poller restart → fetch lại event (vẫn PENDING) → kafka.send lại
→ Duplicate event trên Kafka
```

**Solution: Kafka Producer Idempotency + Consumer-side Dedup**

**Tầng 1 — Kafka Idempotent Producer:**
```yaml
# application.yml
spring.kafka.producer:
  enable-idempotence: true      # Kafka đảm bảo exactly-once per partition
  acks: all                     # Tất cả replicas confirm
  retries: Integer.MAX_VALUE
  max-in-flight-requests-per-connection: 5
```

Với `enable-idempotence=true`, Kafka broker dedup duplicate sends từ cùng 1 producer session trong cùng 1 partition. Tuy nhiên chỉ cover trong 1 producer session — nếu Poller restart thì session mới.

**Tầng 2 — Optimistic Status Update:**
```sql
-- Dùng CAS (Compare-And-Swap) khi update status
UPDATE outbox_events
SET status = 'PUBLISHING',      -- intermediate state
    updated_at = NOW()
WHERE id = ?
  AND status = 'PENDING'        -- chỉ update nếu vẫn PENDING

-- Nếu rowsUpdated = 0 → ai đó đã lấy event này rồi → skip
```

```java
// Sau khi kafka.send() thành công:
UPDATE outbox_events SET status = 'PUBLISHED' WHERE id = ?

// Nếu crash giữa PUBLISHING:
// Cron cleanup: sau 5 phút, reset PUBLISHING → PENDING để retry
UPDATE outbox_events
SET status = 'PENDING'
WHERE status = 'PUBLISHING'
  AND updated_at < NOW() - INTERVAL 5 MINUTE
```

**Tầng 3 — Consumer-side Dedup (section 12.2)** là safety net cuối cùng.

**3 tầng kết hợp:**
```
Outbox Poller     → Kafka Idempotent Producer (tầng 1)
                  → PUBLISHING state (tầng 2)
                  → Consumer ProcessedEvent table (tầng 3)
```

---

### 12.4 Tóm tắt Idempotency Coverage

| Tầng | Vấn đề | Solution | Guarantee |
|---|---|---|---|
| API (client retry) | Client retry → duplicate request | Idempotency-Key header + Redis cache | Exactly-once response |
| Voucher Claim | Race condition 10K users | Redis Lua atomic + FOR UPDATE SKIP LOCKED | Exactly-once claim per user |
| Kafka Consumer | At-least-once redelivery | ProcessedEvent table trong DB transaction | Exactly-once processing |
| Outbox Poller | Crash giữa publish | PUBLISHING state + Kafka idempotent producer | At-most-once duplicate publish |
| Campaign Activation | Multi-pod duplicate scan | RLock + Optimistic Lock | Exactly-once activation |

---

### 12.5 Interview Talking Points — Concurrency & Idempotency

**"Tại sao dùng Lua script thay vì transaction trong Redis?"**
> Redis không có transaction theo nghĩa ACID. MULTI/EXEC là optimistic — nếu key bị thay đổi giữa WATCH và EXEC thì transaction fail và phải retry. Lua script đảm bảo atomicity thật sự vì Redis single-threaded và Lua chạy không bị interrupt.

**"Nếu Redis down thì claim flow xử lý thế nào?"**
> Fallback về DB với pessimistic lock: `SELECT ... FOR UPDATE`. Chậm hơn (~10x) nhưng correct. Circuit Breaker detect Redis down → switch sang DB path. Đây là trade-off giữa availability và performance.

**"Consumer idempotency dùng DB table có bị bottleneck không?"**
> ProcessedEvent INSERT là O(1) với PRIMARY KEY lookup. 10K events/giây là trong tầm của MariaDB với proper indexing. Nếu cần scale hơn: partition processed_events table theo consumer name + date range.

**"Tại sao không dùng Kafka Exactly-Once Semantics (EOS) cho toàn bộ?"**
> Kafka EOS (transactional producer + consumer) có overhead lớn (~3x latency) và chỉ work khi producer và consumer đều trong Kafka ecosystem. Khi consumer cần ghi vào DB (không phải Kafka topic khác), EOS không cover được. Pattern ProcessedEvent table flexible hơn và ít coupling hơn.

**"PUBLISHING state trong Outbox có bị stuck không nếu Poller crash?"**
> Có — đó là lý do cần cleanup cron. Sau 5 phút ở trạng thái PUBLISHING mà không chuyển sang PUBLISHED → reset về PENDING để retry. 5 phút là đủ dài để Kafka publish timeout và đủ ngắn để không block quá lâu.

---

*Last updated: 2026-04-27*

---

## 13. Triết Lý Xây Dựng Hệ Thống

> Phần này không phải lý thuyết sách vở — đây là những quan điểm cần internalize trước khi design bất kỳ hệ thống nào.

---

### 13.1 Định nghĩa "Hệ thống tốt"

> **Hệ thống tốt = làm đúng việc của nó, đủ lâu, với chi phí hợp lý.**

3 vế quan trọng như nhau:
- Nhanh nhưng sai kết quả → vô dụng
- Đúng nhưng chết sau 3 tháng → không tốt
- Hoàn hảo nhưng tốn gấp 10 lần ngân sách → không ai dùng

---

### 13.2 Bảy Nguyên Tắc Cốt Lõi

**1. Correctness — Đúng trước, nhanh sau**

Hệ thống phải cho ra kết quả đúng trong mọi trường hợp kể cả edge case. Đây là nguyên tắc không thể trade-off.

Hay bị vi phạm bởi: race condition không xử lý, thiếu validation, retry không idempotent.

> *"Make it work, then make it fast"*

---

**2. Reliability — Design for failure**

Khi có lỗi xảy ra — và lỗi **sẽ** xảy ra — hệ thống phải fail gracefully, recover automatically, và không mất data.

Assume mọi thứ đều có thể chết: DB, Redis, Kafka, network, pod. Với mọi component, hỏi: *"Nếu cái này chết thì sao?"*

Công cụ: Circuit Breaker, Retry + DLQ, Outbox Pattern, Idempotency.

---

**3. Scalability — Thêm pod, không rewrite**

Horizontal scaling (thêm pod) thay vì vertical scaling (thêm RAM — có giới hạn cứng).

Điều kiện để horizontal scale được:
- Stateless services — state phải ở Redis/DB, không trong memory
- Idempotent operations — thêm pod không sợ duplicate processing
- Partition-friendly data — Kafka partition, DB sharding có thể làm được

---

**4. Maintainability — Người khác đọc được sau 6 tháng**

Code tự document chính nó. Comment giải thích *why*, không phải *what*. Nếu unit test khó viết → đó là signal thiết kế đang có vấn đề, không phải vấn đề của test.

Observability là một phần của maintainability: structured logging, metrics, health check — hệ thống phải tự báo cáo tình trạng của nó.

> *"Code được đọc nhiều hơn được viết"*

---

**5. Performance — Đủ nhanh, không phải nhanh nhất**

Performance = đủ nhanh để đáp ứng SLA với chi phí hợp lý. Không phải "càng nhanh càng tốt".

Cách tiếp cận đúng: Define SLA → Measure baseline → Identify bottleneck (profiling, không đoán) → Fix → Measure lại.

Thứ tự nên thử:
```
1. Fix N+1 query       ← thường là bottleneck lớn nhất, fix dễ nhất
2. Thêm index đúng chỗ
3. Caching đúng layer
4. Async processing
5. Horizontal scaling
```

> *"Premature optimization is the root of all evil"*

---

**6. Security — Built-in, không phải add-on**

Security không thể thêm vào sau khi build xong. Phải thiết kế từ đầu.

- Authentication: verify "mày là ai"
- Authorization: verify "mày được làm gì"
- Input validation: assume mọi input từ client đều có thể malicious
- Least privilege: service chỉ có quyền vừa đủ
- Secrets management: không hardcode, không commit lên Git

> *"Defense in depth"* — không rely vào 1 lớp bảo vệ duy nhất.

---

**7. Simplicity — Đơn giản nhất có thể, không đơn giản hơn**

Đây là nguyên tắc khó nhất vì đòi hỏi discipline để **không** thêm complexity không cần thiết.

> *"Complexity is the enemy of reliability"*

Dấu hiệu over-engineered: dùng microservices cho team 2 người, dùng Kafka cho 100 messages/ngày, implement distributed transaction khi 1 DB là đủ.

> *"You Ain't Gonna Need It (YAGNI)"* — không build cho requirements tưởng tượng.

---

### 13.3 Thứ Tự Ưu Tiên Khi Trade-off

```
Correctness > Reliability > Maintainability > Security > Scalability > Performance
```

**Tại sao Performance xếp cuối:**
- Hệ thống chậm nhưng đúng và tin cậy → user khó chịu nhưng vẫn dùng được
- Hệ thống nhanh nhưng sai hoặc mất data → không ai dùng được

**Tại sao Scalability không phải số 1:**
- 90% hệ thống không bao giờ cần scale đến mức phải redesign
- Over-engineer cho scale sớm là waste of time và tăng complexity không cần thiết

---

### 13.4 "Change là thứ khiến hệ thống sụp đổ"

Đây là quan điểm được tranh luận nhiều. Phân tích thẳng:

**Phần đúng — Change là nguồn gốc phổ biến nhất của outage:**

Phần lớn production outage không đến từ hệ thống đang chạy ổn định mà đến từ deploy mới, config change, schema migration, dependency upgrade. Hệ thống đang sống tốt, con người tác động vào → chết.

Các dạng change hay giết hệ thống:
- **Code change:** Bug được introduce, regression không được catch bởi test
- **Schema change:** ALTER TABLE trên bảng 100M rows không dùng online DDL → lock table → timeout cascade
- **Config change:** Thay 1 giá trị timeout → hệ thống behave hoàn toàn khác dưới load
- **Dependency change:** Upgrade library minor version → breaking change không được document
- **Scale change:** Hệ thống tốt ở 1K users, không test ở 100K → traffic spike → chết

**Phần chưa đủ — "Dễ đáp ứng change" không phải định nghĩa đầy đủ:**

*Vấn đề 1:* Đây là điều kiện cần, không đủ. Hệ thống dễ change nhưng change xong vẫn sai thì không tốt. Maintainability là 1 trong 7 nguyên tắc, không phải nguyên tắc duy nhất.

*Vấn đề 2:* "Dễ change" và "ổn định" thường tension với nhau. Monolith dễ change nhất nhưng deploy 1 thứ ảnh hưởng toàn bộ. Microservices dễ change từng service độc lập nhưng change interface giữa services lại khó và nguy hiểm hơn. Không có kiến trúc nào vừa dễ change mọi thứ vừa ổn định — phải chọn cái gì dễ change và cái gì phải stable.

*Vấn đề 3:* Change không phải kẻ thù — *uncontrolled* change mới là kẻ thù. Hệ thống không change = hệ thống chết. Không có feature mới, không fix bug, không adapt với môi trường thay đổi. Change là tất yếu và cần thiết.

**Kết luận đúng hơn:**

> Hệ thống tốt không phải hệ thống *tránh change* hay *dễ change* — mà là hệ thống làm cho **change trở nên an toàn, observable, và reversible**.

**Làm change an toàn bằng cách nào:**

*Test coverage thực sự:* Mục đích không phải đạt 80% coverage mà là catch regression trước khi lên production. Unit test, integration test, contract test giữa services.

*Deployment strategy:*
- Blue-green: chạy version mới song song, switch traffic khi confident
- Canary release: route 5% traffic sang version mới, watch metrics, rồi rollout 100%
- Feature flag: deploy code mới nhưng chưa enable, enable dần theo %

*Backward compatibility as a discipline:*
- API versioning: không break client cũ khi thêm field mới
- Kafka event schema evolution: thêm field optional, không xóa field cũ
- DB migration: không DROP COLUMN ngay, deprecated trước rồi xóa sau vài deploy

*Observability để detect nhanh:* Change xong phải biết ngay nó có ổn không. P99 latency tăng? Error rate tăng? Nếu không có metrics thì không biết cho đến khi user báo.

*Rollback capability:* Mọi change phải rollback được trong vòng 5 phút. Nếu không rollback được → không deploy.

---

### 13.5 Áp Vào Project Này

| Nguyên tắc | Cover bằng gì trong Promo Engine |
|---|---|
| Correctness | Redis Lua atomic, Optimistic Lock, Campaign state machine |
| Reliability | Outbox Pattern, Retry/DLQ, Circuit Breaker, Idempotency |
| Scalability | Stateless services, Kafka partitioning, horizontal Executor |
| Maintainability | Structured logging, Correlation ID, Testcontainers |
| Performance | Redis caching, Token Bucket, SKIP LOCKED |
| Security | JWT RS256, Refresh token rotation, Rate limiting |
| Simplicity | ⚠️ Điểm yếu — 6 services cho portfolio hơi nhiều, phải justify rõ từng service khi interview |

**Về change safety trong project này:**
- Kafka event schema dùng field optional, không xóa field cũ → consumer cũ không bị break
- API versioning: prefix `/v1/` từ đầu → dễ introduce `/v2/` sau
- DB migration dùng Flyway/Liquibase → version-controlled, reproducible, rollback được
- Feature flag cho campaign activation → disable tính năng mới mà không cần redeploy

---

*Last updated: 2026-04-27*

---

## 14. Spring Project Definitions

---

### 14.1 Parent POM

```
Group:      com.promoengine
Artifact:   promo-engine-parent
Packaging:  pom
Java:       17
```

Quản lý version tập trung cho tất cả services. Upgrade Spring Boot 1 chỗ, không sửa 6 file.

```xml
<modules>
    <module>auth-service</module>
    <module>api-gateway</module>
    <module>campaign-service</module>
    <module>voucher-service</module>
    <module>notification-service</module>
    <module>executor-service</module>
</modules>
```

---

### 14.2 Auth Service

```
Group:      com.promoengine
Artifact:   auth-service
Package:    com.promoengine.auth
Java:       17
```

**Spring Initializr dependencies:**
- Spring Web
- Spring Security
- Spring Data JPA
- Spring Validation
- Flyway Migration
- MariaDB Driver
- Spring Data Redis
- Lombok
- Spring Boot Actuator

**Manual dependencies (thêm vào pom.xml sau):**
- Redisson Spring Boot Starter
- JJWT (api + impl + jackson)
- MapStruct

---

### 14.3 API Gateway

```
Group:      com.promoengine
Artifact:   api-gateway
Package:    com.promoengine.gateway
Java:       17
```

**Spring Initializr dependencies:**
- Spring Cloud Gateway (Reactive)
- Spring Security
- Spring Data Redis Reactive
- Lombok
- Spring Boot Actuator

**Manual dependencies:**
- JJWT (verify only, không issue)

> ⚠️ Gateway dùng Reactive stack (WebFlux). KHÔNG add Spring Web — conflict với Gateway.

---

### 14.4 Campaign Service

```
Group:      com.promoengine
Artifact:   campaign-service
Package:    com.promoengine.campaign
Java:       17
```

**Spring Initializr dependencies:**
- Spring Web
- Spring Security
- Spring Data JPA
- Spring Validation
- Flyway Migration
- MariaDB Driver
- Spring for Apache Kafka
- Spring Data Redis
- Lombok
- Spring Boot Actuator

**Manual dependencies:**
- Redisson Spring Boot Starter
- MapStruct

---

### 14.5 Voucher Service

```
Group:      com.promoengine
Artifact:   voucher-service
Package:    com.promoengine.voucher
Java:       17
```

**Spring Initializr dependencies:**
- Spring Web
- Spring Security
- Spring Data JPA
- Spring Validation
- Flyway Migration
- MariaDB Driver
- Spring for Apache Kafka
- Spring Data Redis
- Lombok
- Spring Boot Actuator

**Manual dependencies:**
- Redisson Spring Boot Starter
- MapStruct

---

### 14.6 Notification Service

```
Group:      com.promoengine
Artifact:   notification-service
Package:    com.promoengine.notification
Java:       17
```

**Spring Initializr dependencies:**
- Spring Web
- Spring Security
- Spring Data JPA
- Spring Validation
- Flyway Migration
- MariaDB Driver
- Spring for Apache Kafka
- Spring Data Redis
- Java Mail Sender
- Lombok
- Spring Boot Actuator

**Manual dependencies:**
- Redisson Spring Boot Starter
- MapStruct
- Resilience4j Spring Boot Starter 3

---

### 14.7 Executor Service

```
Group:      com.promoengine
Artifact:   executor-service
Package:    com.promoengine.executor
Java:       17
```

**Spring Initializr dependencies:**
- Spring Web
- Spring Data JPA
- Spring Validation
- Flyway Migration
- MariaDB Driver
- Spring for Apache Kafka
- Spring Data Redis
- Lombok
- Spring Boot Actuator

**Manual dependencies:**
- Redisson Spring Boot Starter
- MapStruct

> Spring Scheduling built-in, không cần thêm dependency riêng.

---

### 14.8 Manual Dependencies — Version Reference

Thêm tay vào pom.xml sau khi generate từ Initializr:

**Redisson** (tất cả services trừ Gateway):
```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
```

**JJWT** (Auth Service + Gateway):
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
```

**MapStruct** (tất cả services trừ Gateway):
```xml
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>1.5.5.Final</version>
</dependency>
```

**MapStruct annotation processor** — thêm vào `maven-compiler-plugin`:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </path>
            <!-- Lombok TRƯỚC MapStruct — bắt buộc -->
            <path>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct-processor</artifactId>
                <version>1.5.5.Final</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

**Resilience4j** (Notification Service only):
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
```

---

### 14.9 Cấu Trúc Thư Mục

```
promo-engine/
├── pom.xml                    ← parent pom
├── docker-compose.yml
├── README.md
├── docs/
│   └── architecture.md
├── auth-service/
│   ├── pom.xml
│   └── src/
├── api-gateway/
│   ├── pom.xml
│   └── src/
├── campaign-service/
│   ├── pom.xml
│   └── src/
├── voucher-service/
│   ├── pom.xml
│   └── src/
├── notification-service/
│   ├── pom.xml
│   └── src/
└── executor-service/
    ├── pom.xml
    └── src/
```

---

### 14.10 Port Allocation

| Service | Port |
|---|---|
| API Gateway | 8080 |
| Auth Service | 8081 |
| Campaign Service | 8082 |
| Voucher Service | 8083 |
| Notification Service | 8084 |
| Executor Service | 8085 |
| MariaDB | 3306 |
| Redis | 6379 |
| Kafka | 9092 |
| Kafka UI | 8090 |
| Mailhog SMTP | 1025 |
| Mailhog Web UI | 8025 |

---

*Last updated: 2026-04-27*
