# JWT Authentication — Implementation Guide

> auth-service · Spring Boot 3.3.5 · Spring Security 6 · JJWT 0.12.5 · MariaDB · Redis (Redisson)
> Cấu trúc: N-tiered với naming familiar (controller / service / repository / entity / config / security / exception)

---

## PHẦN 1 — Kiến trúc Spring Security (cần hiểu trước khi code)

### 1.1. Big picture

Spring Security = **chuỗi Servlet Filter** chặn mọi HTTP request **trước khi** vào Controller. Chuỗi này xác định:
- **Authentication**: ai đang gọi?
- **Authorization**: có quyền truy cập resource này không?

```
HTTP Request
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│  SecurityFilterChain (config từ SecurityConfig.java)    │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌──────┐ │
│  │ Filter 1  │→ │ Filter 2  │→ │JwtAuthFilt│→ │ ...  │ │
│  └───────────┘  └───────────┘  └─────┬─────┘  └──────┘ │
│                                      │                  │
│                                      ▼                  │
│                          SecurityContextHolder          │
│                          (lưu user đã auth)             │
└─────────────────────────────────────────────────────────┘
    │
    ▼
@RestController → method handler
    (truy cập user qua SecurityContextHolder hoặc @AuthenticationPrincipal)
```

### 1.2. Các thành phần cốt lõi và vai trò

| Component | Vai trò | Class tao sẽ tạo |
|---|---|---|
| **`SecurityFilterChain`** | Bean config tổng: URL nào public, URL nào cần auth, filter nào chạy | `config/SecurityConfig` |
| **`UserDetails`** | Wrapper object Spring hiểu được (username, password hash, authorities) | `security/UserDetailsImpl` |
| **`UserDetailsService`** | Load `UserDetails` từ DB theo username | `security/UserDetailsServiceImpl` |
| **`AuthenticationProvider`** | Logic validate credentials | `DaoAuthenticationProvider` (Spring tự cấu hình khi có `UserDetailsService` + `PasswordEncoder` beans) |
| **`AuthenticationManager`** | Orchestrator gọi `AuthenticationProvider` | Lấy bean từ `AuthenticationConfiguration` |
| **`PasswordEncoder`** | Hash & verify password | `BCryptPasswordEncoder` (bean trong `SecurityConfig`) |
| **JWT Filter** | Đọc Bearer token, parse, set `SecurityContext` cho request | `security/JwtAuthFilter extends OncePerRequestFilter` |
| **`AuthenticationEntryPoint`** | Handle khi unauthenticated request gọi protected endpoint → trả 401 JSON | `security/AuthEntryPointJwt` |
| **`SecurityContextHolder`** | ThreadLocal storage cho `Authentication` của request hiện tại | Spring quản lý |

### 1.3. Stateful (default) vs Stateless (JWT) — key insight

**Default Spring Security:**
- Login → tạo `HttpSession` server-side → cookie `JSESSIONID` về client
- Mỗi request kế tiếp mang cookie → server lookup session → biết user
- Cần CSRF protection vì cookie tự động gửi cùng request

**JWT (cái tao đang làm):**
- Login → server ký JWT (access + refresh) → trả body
- Mỗi request client tự mang `Authorization: Bearer <token>`
- Server **không lưu session** — mỗi request standalone, chỉ verify chữ ký
- **Không cần CSRF** (không có cookie tự động)
- Config bắt buộc: `sessionCreationPolicy(STATELESS)` + `csrf().disable()`

### 1.4. Flow A — Login (cấp token)

```
Client                Controller            AuthenticationManager      UserDetailsService    DB
  │                       │                          │                          │              │
  │ POST /auth/login      │                          │                          │              │
  │ {username, password}  │                          │                          │              │
  ├──────────────────────▶│                          │                          │              │
  │                       │ authenticate(            │                          │              │
  │                       │   UsernamePasswordToken) │                          │              │
  │                       ├─────────────────────────▶│                          │              │
  │                       │                          │ loadUserByUsername(u)    │              │
  │                       │                          ├─────────────────────────▶│              │
  │                       │                          │                          │ findBy...    │
  │                       │                          │                          ├─────────────▶│
  │                       │                          │                          │◀─────────────┤
  │                       │                          │◀─────────────────────────┤              │
  │                       │                          │ (BCrypt match password)  │              │
  │                       │◀─────────────────────────┤                          │              │
  │                       │ JwtService.sign(access)  │                          │              │
  │                       │ JwtService.sign(refresh) │                          │              │
  │                       │ TokenService.store(rt)   │                          │              │
  │                       │                          │                          │              │
  │ 200 {access, refresh} │                          │                          │              │
  │◀──────────────────────┤                          │                          │              │
```

### 1.5. Flow B — Authenticated request (dùng access token)

```
Client                JwtAuthFilter              SecurityContextHolder      Controller
  │                        │                            │                       │
  │ GET /api/protected     │                            │                       │
  │ Authorization: Bearer..│                            │                       │
  ├───────────────────────▶│                            │                       │
  │                        │ extract token              │                       │
  │                        │ jwtService.parseClaims()   │                       │
  │                        │ verify signature + expiry  │                       │
  │                        │ build Authentication       │                       │
  │                        ├───────────────────────────▶│                       │
  │                        │                            │ chain.doFilter()      │
  │                        ├──────────────────────────────────────────────────▶│
  │                        │                            │                       │ handler
  │ 200 {data}             │                            │                       │
  │◀──────────────────────────────────────────────────────────────────────────┤
```

### 1.6. Flow C — Refresh token rotation

```
Client                Controller            JwtService            TokenService(Redis)
  │                       │                       │                       │
  │ POST /auth/refresh    │                       │                       │
  │ {refreshToken}        │                       │                       │
  ├──────────────────────▶│                       │                       │
  │                       │ parseClaims(rt)       │                       │
  │                       ├──────────────────────▶│                       │
  │                       │◀──────────────────────┤ (jti, sub)            │
  │                       │ exists(rt:{sub}:{jti})│                       │
  │                       ├──────────────────────────────────────────────▶│
  │                       │◀──────────────────────────────────────────────┤
  │                       │   ─ nếu KHÔNG tồn tại → reuse detection       │
  │                       │     → revoke ALL tokens của user             │
  │                       │     → throw 401                              │
  │                       │   ─ nếu tồn tại:                              │
  │                       │     1. delete(rt:{sub}:{jti})  ← rotate       │
  │                       │     2. sign new access + refresh              │
  │                       │     3. store new rt:{sub}:{newJti}            │
  │ 200 {access, refresh} │                       │                       │
  │◀──────────────────────┤                       │                       │
```

---

## PHẦN 2 — Overview các phase implement

### Phase 1 · Foundation (Entity + Repository)
**Goal:** Có model JPA + repo để CRUD user.

| File | Trách nhiệm |
|---|---|
| `entity/enums/UserRole` | Enum: `USER`, `ADMIN` |
| `entity/enums/UserStatus` | Enum: `UNVERIFIED`, `ACTIVE`, `BLOCKED`, `DELETED` |
| `entity/UserEntity` | JPA `@Entity` map sang table `users` (id, username UNIQUE, email UNIQUE, passwordHash, role, status, createdAt, updatedAt) |
| `repository/UserRepository` | `extends JpaRepository<UserEntity, Long>` + `findByUsername`, `findByEmail`, `existsByUsername`, `existsByEmail` |

**Verify:** App start được, table `users` tự tạo (ddl-auto=update).

---

### Phase 2 · JWT Keys (Cryptography config)
**Goal:** Load RSA keys từ classpath thành Spring beans.

| File | Trách nhiệm |
|---|---|
| `config/properties/JwtProperties` | `@ConfigurationProperties("app.jwt")` chỉ chứa `accessTokenTtl`, `refreshTokenTtl` (long, giây) |
| `config/JwtConfig` | `@Bean RSAPrivateKey` + `@Bean RSAPublicKey` — inject `Resource` qua `@Value` (KHÔNG để trong `JwtProperties` vì Binder không hỗ trợ Resource), dùng `RsaKeyConverters` parse |

**Verify:** App start, autowire `RSAPrivateKey/PublicKey` ở chỗ khác → không null.

---

### Phase 3 · Spring Security adapters
**Goal:** Spring Security biết cách load user của mình từ DB.

| File | Trách nhiệm |
|---|---|
| `security/UserDetailsImpl` | `implements UserDetails` — wrap `UserEntity`, expose `username`, `passwordHash`, `authorities` (= `ROLE_<role>`), `isEnabled` (= `status == ACTIVE`) |
| `security/UserDetailsServiceImpl` | `implements UserDetailsService` — `loadUserByUsername()` → query repo → wrap thành `UserDetailsImpl`, throw `UsernameNotFoundException` |

**Verify:** Unit test load user existed → OK; user không tồn tại → throw.

---

### Phase 4 · JWT Service (sign + parse + verify)
**Goal:** Service tập trung tạo và xác thực JWT.

| File | Trách nhiệm |
|---|---|
| `service/JwtService` (interface) | `generateAccessToken(UserEntity)`, `generateRefreshToken(UserEntity)` (returns `{token, jti}`), `parseClaims(token)`, `getUserId(token)`, `isExpired(token)` |
| `service/impl/JwtServiceImpl` | Inject `RSAPrivateKey`, `RSAPublicKey`, `JwtProperties`. Dùng JJWT 0.12.5 API (`Jwts.builder()...signWith(privateKey)...compact()` + `Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token)`). Claims: `sub`=userId, `username`, `email`, `role`, `iat`, `exp`, refresh thêm `jti` |

**Verify:** Unit test sign → parse → claims khớp; token expired → throw `ExpiredJwtException`.

---

### Phase 5 · JWT Filter + SecurityConfig
**Goal:** Mỗi request có Bearer token được authenticate tự động.

| File | Trách nhiệm |
|---|---|
| `security/AuthEntryPointJwt` | `implements AuthenticationEntryPoint` — write 401 JSON (`{error, message}`) khi unauthenticated |
| `security/JwtAuthFilter` | `extends OncePerRequestFilter` — đọc `Authorization` header, nếu có Bearer: parse claims → load `UserDetails` qua `UserDetailsService` → set `SecurityContextHolder`. Skip nếu URL public |
| `config/SecurityConfig` | `@EnableWebSecurity` + `@EnableMethodSecurity` — bean `SecurityFilterChain`: stateless, csrf disabled, exception handler → `AuthEntryPointJwt`, public endpoints `/auth/**` + `/actuator/**`, addFilterBefore(`JwtAuthFilter`, `UsernamePasswordAuthenticationFilter`). Bean `PasswordEncoder` (BCrypt) + `AuthenticationManager` |

**Verify:** Hit `/api/me` không token → 401; có token valid → 200.

---

### Phase 6 · Redis token storage
**Goal:** Refresh token rotation + reuse detection + revoke (logout).

| File | Trách nhiệm |
|---|---|
| `config/RedisConfig` | `@Bean RedissonClient` từ `application.properties` |
| `service/RedisService` (interface) + impl | Generic wrapper: `set(key, value, ttl)`, `get(key)`, `delete(key)`, `keys(pattern)`, `exists(key)` |
| `service/TokenService` (interface) + impl | Domain-specific: `storeRefreshToken(userId, jti, ttl)`, `validateRefreshToken(userId, jti)`, `rotateRefreshToken(userId, oldJti, newJti, ttl)`, `revokeAllForUser(userId)`. Key pattern: `rt:{userId}:{jti}` |

**Verify:** Round-trip — store, validate true → rotate → old key gone, new key tồn tại; reuse old jti → revokeAll triggered.

---

### Phase 7 · Business logic + REST API
**Goal:** Hoàn thiện endpoint cho register, login, refresh, logout, verify email.

| File | Trách nhiệm |
|---|---|
| `exception/*` | `DuplicateUsernameException`, `DuplicateEmailException`, `UserNotFoundException`, `InvalidTokenException`, `EmailNotVerifiedException` |
| `exception/GlobalExceptionHandler` | `@RestControllerAdvice` map từng exception → HTTP status + body chuẩn (no stack trace leak) |
| `controller/dto/request/*` | `RegisterRequestDTO` (username, email, password — `@Valid`), `LoginRequestDTO`, `RefreshTokenRequestDTO`, `VerifyEmailRequestDTO`, `ResendVerificationRequestDTO` |
| `controller/dto/response/*` | `TokenResponseDTO {accessToken, refreshToken, expiresIn}`, `UserResponseDTO {id, username, email, role}` |
| `service/AuthService` (interface) + impl | `register`, `login`, `refresh`, `logout`, `verifyEmail`, `resendVerification` |
| `controller/AuthController` (interface) + impl | `@PostMapping` 6 endpoints, delegate xuống `AuthService` |

**Verify:** Test full flow bằng `auth.http`:
1. register → 201 (status `UNVERIFIED`)
2. verifyEmail (lấy token từ Redis hoặc log) → 200 (status `ACTIVE`)
3. login → 200 + tokens
4. GET protected with access → 200
5. refresh → 200 + new tokens, old refresh chết
6. logout → 200, refresh không xài lại được

---

### Phase 8 (optional) · Email sender
- Tạm thời log token verify ra console hoặc lưu Redis key `email:verify:{token}` để dev tự lấy
- Real impl: `spring-boot-starter-mail` + `JavaMailSender` (sau)

---

## Dependency graph giữa các phase

```
Phase 1 (Entity + Repo)
        │
        ├──────────────┐
        ▼              ▼
Phase 3 (UserDetails)  Phase 6 (Redis)  ← parallel
        │                  │
Phase 2 (JWT keys)         │
        │                  │
        ▼                  │
Phase 4 (JwtService)       │
        │                  │
        └────┬─────────────┘
             ▼
Phase 5 (Filter + SecurityConfig)
             │
             ▼
Phase 7 (DTO + AuthService + Controller + ExceptionHandler)
             │
             ▼
Phase 8 (Email — optional)
```

---

## Checklist trước khi bắt đầu code

- [ ] Đọc xong PHẦN 1 — hiểu rõ vai trò từng component (`UserDetails`, `UserDetailsService`, `AuthenticationManager`, `SecurityFilterChain`, `JwtAuthFilter`, `SecurityContextHolder`)
- [ ] Đọc xong PHẦN 2 — biết tổng cộng có 7 phase (+1 optional), output từng phase là gì, verify từng phase ra sao
- [ ] Cấu trúc package theo N-tiered đã approve (controller / service / repository / entity / config / security / exception)
- [ ] `application.properties`, `pom.xml`, `certs/private.pem`, `certs/public.pem` đã sẵn sàng
- [ ] Chỉ còn `AuthServiceApplication.java` trong Java sources — clean slate

→ **Khi tao OK 3 cái trên, qua PHẦN 3 (chi tiết từng phase, code snippets, gotchas).**
