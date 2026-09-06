# Android ↔ FR Monolith Backend Integration — Progress

## 1. Objective

Update the Android app at `First/` so it communicates correctly with the new monolithic FR
backend at `FR BACKEND MSC/` (Kafka-based microservices removed, replaced by a single Spring Boot
app), **without changing any existing FR functionality**: registration, verification, face
capture, liveness, anti-spoofing, camera behaviour, image processing, NIC/OCR, user/UI flow,
validation logic, or business outcomes. Scope is strictly the communication/integration layer.

## 2. Backend Communication Analysis

**Stack**: Spring Boot 3.3.1 / Java 17, Maven, single module `lk.cf.fr.monolith`, H2 in-memory DB,
listens on `0.0.0.0:8090`, no context path, no Spring Security/CORS config (unauthenticated,
same as before). Kafka is **fully removed** — confirmed via `pom.xml` (no kafka artifact) and a
project-wide grep (only prose comments referencing the old Kafka mechanism remain, no code).

### HTTP endpoints (source of truth: `controller/*.java`)

| Verb | Path | Body | Notes |
|---|---|---|---|
| `POST` | `/api/facial-auth` | multipart: text part `data` = JSON `RegistrationRequest`, optional file part `scannedNIC` | Registration. Returns `200` + `RegistrationResponse` even when comparisons fail (only genuine failures are non-2xx). |
| `POST` | `/api/validation` | JSON `{"data": VerificationRequest}` (must be wrapped in `data`) | Verification. Face-mismatch/liveness-fail → `400` + full `VerificationResponse`. |
| `GET` | `/records/{nic}` | — | Returns raw `RegistrationRecord` JPA entities (admin/lookup use). |
| `PUT` | `/records/{referenceId}/{status}` | — | Approve/reject a registration record. |

**Important**: these HTTP endpoints are **not called by the Android app**. They are the surface a
separate caller (teller/agent portal, Postman, etc.) uses to *start* a registration/verification.
The Android app's only role is as the **capture device**, reached exclusively over WebSocket.

### WebSocket — the device channel (`/device/ws-endpoint`)

Plain Spring `WebSocketHandler` (not STOMP/SockJS), config in `device/DeviceWebSocketConfig.java`.
Handshake requires 4 headers, checked in `device/DeviceWebSocketHandler.handshakeIsValid`:
`X-Device-Id` (must match `^[0-9]{8}$`), `X-Android-Id`, `X-Package-Name` (must equal
`com.example.helloonepage`, case-insensitive), `X-Widevine-Id` (presence only).

Every message (both directions) must carry `type`, `timestamp` (rejected if >30s skew) and
`messageId` (duplicates within 30s silently dropped) — see `handleMessage()`.

**Backend only actively handles these inbound types** (everything else is logged and ignored):
`hello`, `image`, `camera-timeout-no-face`, `camera-cancelled-by-user`, and any of
`liveness-result` / `liveness-complete` / `liveness-finished` (used interchangeably as the
"on-device liveness challenge finished" signal). Correlation for image and liveness completion is
by `referenceId` alone (`device/DeviceCommunicationService`) — a single pending capture/liveness
wait per `referenceId` at a time.

**Backend pushes**, sequentially, one `open-camera` command per capture tag (waits for the
`image` reply for one tag before sending the next):
- Verification: one `open-camera` (`flow:"verify"`, `tag:"faceImage"`).
- Registration: three, in order — `tag:"nicImage"` (OCR-validated, retried up to
  `registration.nic-max-retries`, default 1 retry) → `tag:"faceImage"` → `tag:"selfImage"`.
  All three additionally carry `data.mode`, exactly `"Auto"` or `"Manual"` — the capture mode the
  teller picked for this registration. **Registration only**: the verification `open-camera` has
  no `mode` key at all. `Manual` is the device's cue to show its manual capture button for that
  step; `Auto` is the pre-existing automatic behaviour. Older callers that omit `mode` on
  `POST /api/facial-auth` still get `"Auto"` (`registration/model/RegistrationMode`), so a device
  build that ignores the field behaves exactly as before.

It also pushes `{"type":"liveness-session-created","data":{"sessionId","referenceId"}}` once per
flow (proactively, not as a reply to any client request).

**Explicitly out of scope in this backend** (per its own class-doc comment):
`activate`, `request-liveness-session`, `liveness-cancelled` — these are accepted on the wire
(no crash) but produce no response and no side effect.

### DTOs (verbatim, see backend source for full listing)
- `RegistrationRequest` / `RegistrationResponse` (`registration/dto/`)
- `VerificationRequest` wrapped in `VerificationRequestEnvelope{ data }` / `VerificationResponse` (`verification/dto/`)
- `RegistrationRecord` entity (raw, returned by `/records/**`)

### Error handling (`controller/GlobalExceptionHandler.java`)
- Missing required field → `400` + `{"status":false,"message":"..."}` (plain map, not the DTO shape).
- Domain failures (device busy/unavailable, camera error, NIC invalid, timeout) → `400`/`500`/`504`
  + the **full** `RegistrationResponse`/`VerificationResponse` shape with `reason` set.
- Registration face/liveness mismatch is **not** an HTTP error — `200` with `status:true`,
  `overallSimilarityDecision:"unsuccess"`.

### Device provisioning
There is **no activation/pairing HTTP or WS endpoint** in this backend — confirmed by its own
`data.sql` comment: *"the real cf-fr-server system populates DEVICE_RECORDS via a
device-pairing/activation flow... out of scope for this verification-only MVP."* Devices must be
pre-provisioned directly in `device_records` (status `ACTIVE`, matching `branchId` +
`deviceNickname`, resolved by `registry/DeviceResolutionService`). One demo row is seeded:
`device_id='88806537', branch_id='BR001', device_nickname='DemoDevice', user_id='dasun'`.

### Config (`src/main/resources/application.yml`)
`server.port: 8090`, `server.address: 0.0.0.0`, `verification.similarity-threshold: 80`,
`verification.liveness-confidence-threshold: 65` (configured but not read by the mock path — see
Blockers), device/liveness timeouts default 120s/60s, `aws.enabled: false` (mock services active
by default; flip to `true` + fill AWS fields to use real Rekognition).

## 3. Android Changes

| File | What changed | Why |
|---|---|---|
| [WebSocketService.kt](../First/app/src/main/java/com/example/helloonepage/WebSocketService.kt) | `sendLivenessResult()` gained a `referenceId: String? = null` parameter, included in the outgoing `data` object when present. | The backend correlates liveness completion to the pending wait **by `referenceId`** (`DeviceCommunicationService.onLivenessComplete`), not `sessionId`. The cancel/error path's `liveness-result` message had no `referenceId` at all, so a cancelled/failed on-device liveness challenge would never unblock the backend's wait — it would sit for the full `liveness-wait-timeout-seconds` (60s) before failing as a `TIMEOUT` instead of failing immediately with the real reason. Corresponds to backend `device/DeviceWebSocketHandler.handleMessage` → `case "liveness-result" ... -> onLivenessComplete(referenceId)`. |
| [LivenessActivity.kt](../First/app/src/main/java/com/example/helloonepage/LivenessActivity.kt) (`notifyBackendTerminated`) | Pass `referenceId = referenceId` into the `sendLivenessResult(...)` call. | Wires the above fix through the one call site that needed it. The success path (`handleLivenessComplete`, sends `type:"liveness-finished"`) already included `referenceId` and needed no change. |
| [app/build.gradle.kts](../First/app/build.gradle.kts) | Added `buildConfigField("String", "FR_SERVER_WS_URL", ...)`, sourced from an optional Gradle property, defaulting to `ws://10.0.2.2:8090/device/ws-endpoint`. | The old code had the WS URL as a hardcoded `private const val SERVER_URL = "ws://localhost:8090/device/ws-endpoint"` with ~8 commented-out alternates from past manual environment switching. Requirement: "keep configuration easy to change between local/device/production" and "do not use localhost incorrectly when the backend runs on a separate machine." `10.0.2.2` is the standard emulator alias for the host loopback; `localhost` on-device/emulator means the device itself, not the dev PC. |
| [WebSocketService.kt](../First/app/src/main/java/com/example/helloonepage/WebSocketService.kt) | `SERVER_URL` now reads `BuildConfig.FR_SERVER_WS_URL`; removed the ~8 dead commented-out URL alternates. | Same as above — single source of truth, switch environments via `gradle.properties`/`-P` flag instead of editing source. |
| [gradle.properties](../First/gradle.properties) | Added a commented `FR_SERVER_WS_URL=` example with guidance for emulator/device/production. | Documents how to override without touching code. |
| [network_security_config.xml](../First/app/src/main/res/xml/network_security_config.xml) | Added `10.0.2.2` to the cleartext-allowed domain list. | The app targets SDK 35; a network-security-config with no `<base-config>` defaults cleartext to **denied** for unlisted hosts regardless of the manifest's `usesCleartextTraffic`. Without this, the new emulator-default WS URL would be silently blocked. |
| Deleted: `ReferenceWebSocketConfigBackend.java`, `ReferenceListenerService.java`, `ReferneceCaptureBroker.java`, `ReferenceWebSocketService.kt`, `ReferenceLivenessActivity.kt`, `ReferenceRegisterCaptureActivity.kt` | Removed. | Fully commented-out, undeclared-in-manifest, zero-reference dead files left over from the Kafka-based backend (verified via `grep -cv '^\s*(//|/\*|\*)'` = 0 non-comment lines each, and a repo-wide reference search finding zero usages). They contained the old Kafka producer/consumer/topic logic as inert comments; deleting them satisfies "remove Kafka-related communication where it is no longer required" with zero behavioral risk since nothing compiled or referenced them. |

**Everything else in the Android WebSocket protocol was already compatible and required no
change** — see §5 for why.

## 4. Kafka Removal

Kafka was **never linked into the Android app** — no Kafka client dependency, no producer/consumer
code compiled into the APK. It existed exclusively as internal infrastructure inside the *old*
backend (`Device_Management` ↔ `Face_Recognition` ↔ `Transaction` microservices), invisible to the
device. The only artifacts of it inside the Android source tree were six fully-commented-out
"Reference*" files kept as developer documentation of the old server's internals — deleted (§3).

There is therefore no "Kafka request → HTTP request" migration to perform on the Android side: the
device already talked WebSocket-only, and the new monolith **keeps the same WebSocket contract**
(same host:port pattern `.../device/ws-endpoint`, same handshake headers, same message shapes for
`hello`/`image`/camera-error/liveness-completion) as a direct, in-process replacement for what used
to be Kafka-mediated on the backend's internal side. The equivalent "HTTP endpoint that now
performs what Kafka used to front" is `POST /api/facial-auth` (registration) and
`POST /api/validation` (verification) — but those are called by whatever external system starts a
registration/verification (teller portal), not by the Android app itself.

## 5. API / Protocol Mapping

### WebSocket (the only channel the Android app uses)

| Concern | Old (assumed Kafka-backed) contract the app already implemented | New backend | Required change |
|---|---|---|---|
| Endpoint | `ws(s)://<host>:<port>/device/ws-endpoint` | same path; backend port `8090` | Made host/port configurable (§3); path unchanged |
| Handshake headers | `X-Device-Id` (8-digit), `X-Android-Id`, `X-Package-Name=com.example.helloonepage`, `X-Widevine-Id` | Validates exactly these 4 | **None** — already matches |
| `hello` | `{deviceId, packageName, versionName, versionCode, platform, app}` | reads `data.deviceId`, defaults `clientType` to `"fr"` if absent | **None** |
| Capture push | server sends `open-camera` **per tag**, sequentially, waits for the matching `image` reply before the next tag | identical model (`DeviceCommunicationService.requestCapture`, one pending capture per `referenceId`) | **None** — `WebSocketService.handleCommand()` already dispatches per-tag, including re-dispatching to an already-active `RegisterCaptureActivity`/`VerifyActivity` via `emit(raw)` rather than restarting it |
| `image` reply | `{referenceId, content(base64), ...}` | reads `data.referenceId` + `data.content` | **None** |
| `camera-timeout-no-face` / `camera-cancelled-by-user` | includes `referenceId` | reads `data.referenceId` | **None** |
| `liveness-session-created` (push) | app caches it for late-attaching listeners | identical push, same shape | **None** |
| Liveness completion (success) | app sends `type:"liveness-finished"` with `data.referenceId` already set | accepts `liveness-finished` as a completion signal, keyed by `referenceId` | **None** |
| Liveness completion (cancel/error) | app sent `type:"liveness-result"` **without** `referenceId` | accepts `liveness-result`, but only if `data.referenceId` is present | **Fixed** — added `referenceId` param/plumbing (§3) |
| `activate` (device pairing) | app sends it, awaits a `replyTo`-correlated reply | not handled at all (no pairing feature in this MVP) | **No code change** — see §8 Blocker #1: harmless in practice because the new backend never sends `activation-required` (the only trigger that launches `ActivationCodeActivity`), so this path is simply never entered against the new backend |
| `request-liveness-session` | app sends it, but drives its liveness UI off the async `liveness-session-created` push/cache regardless of any reply | not handled | **None** — no-op, backend pushes `liveness-session-created` on its own timeline either way |
| `images` (combined, end of registration capture) | app sends it after all 3 individual `image` messages | not handled | **None** — harmless no-op; the backend's registration flow is driven entirely by the 3 individual per-tag `image` replies |
| `registration.updateLiveness`, `liveness-cancelled` | app sends these | not handled | **None** — harmless no-ops (see §8 for the one real side-effect of `liveness-cancelled` being ignored) |

### HTTP (informational — not exercised by the Android app itself, but here for completeness /
whoever builds or tests the caller side)

| Android-adjacent concept | Backend endpoint | Method | Request body | Response body | Expected result |
|---|---|---|---|---|---|
| Start registration | `/api/facial-auth` | POST | multipart `data` (JSON `RegistrationRequest`) + optional `scannedNIC` file | `RegistrationResponse` | `200` even on comparison mismatch; orchestrates the 3-tag WS capture + liveness against whichever device resolves for `branchId`+`deviceNickname` |
| Start verification | `/api/validation` | POST | JSON `{"data": VerificationRequest}` | `VerificationResponse` | `200` on match+liveness pass; `400` with reason on mismatch/liveness-fail/device issues |

## 6. WebSocket Mapping (detail)

- **Connection URL**: `BuildConfig.FR_SERVER_WS_URL` (default `ws://10.0.2.2:8090/device/ws-endpoint`; override via Gradle property — see §7).
- **Sequence — Verification**: `hello` (on connect) → backend pushes `open-camera{tag:"faceImage",flow:"verify"}` → device replies `image{referenceId,content}` → backend pushes `liveness-session-created{sessionId,referenceId}` → device runs AWS Amplify Face Liveness UI directly against AWS → device sends `liveness-finished`/`liveness-result{referenceId}` → backend resolves the real liveness outcome itself (mock config or AWS `GetFaceLivenessSessionResults`, **not** from the device's `passed`/`score` fields) → HTTP caller receives `VerificationResponse`.
- **Sequence — Registration**: backend pushes `open-camera{tag:"nicImage",mode:"Auto"|"Manual"}` (device replies `image`; retried once on invalid OCR) → `open-camera{tag:"faceImage"}` → `open-camera{tag:"selfImage"}` → backend had already pushed `liveness-session-created` right at the start → device runs liveness UI (any time after the session is created) → device sends `liveness-finished`/`liveness-result{referenceId}` → HTTP caller receives `RegistrationResponse`.
- **Message envelope**: `{type, timestamp(epoch ms), messageId(uuid), data:{...}}` on every message either direction; already produced by `WebSocketService.addWsSecurityFields()` for every outgoing message.

## 7. Configuration

- **Backend**: `server.port=8090`, `server.address=0.0.0.0` (`FR BACKEND MSC/src/main/resources/application.yml`). Run with `mvnw.cmd spring-boot:run` from `FR BACKEND MSC/`. H2 in-memory DB resets on every restart — `data.sql` reseeds the demo device/registration each time.
- **Android**: `FR_SERVER_WS_URL` build-config field (`First/app/build.gradle.kts`), overridable via `First/gradle.properties` (commented example provided) or `-PFR_SERVER_WS_URL=...` on the Gradle command line:
  - Emulator → dev machine: `ws://10.0.2.2:8090/device/ws-endpoint` (current default)
  - Physical device → dev machine over LAN: `ws://<dev-machine-LAN-IP>:8090/device/ws-endpoint` (add that IP to `network_security_config.xml`'s cleartext allow-list if not already present)
  - Production: `wss://cfmobile.lk/device/ws-endpoint` (already denied for cleartext in the network security config; requires real TLS termination in front of the backend, and the pinned cert in `app/src/main/res/raw/server_cert.crt` must match)
- No secrets are stored in either config beyond the existing AWS Cognito Identity Pool ID (already public-safe by AWS design) and the demo H2 in-memory DB.

## 8. Blockers / Known Limitations

1. **Device pairing/activation has no backend implementation.** `ActivationCodeActivity`'s
   `type:"activate"` WS message is accepted on the wire but never answered (no `device-activated`/
   `activation-required` reply). *Impact*: none in practice — the new backend never sends
   `activation-required` (the only trigger that launches `ActivationCodeActivity`), so this screen
   is simply never entered when talking to the new backend. *Resolution*: none needed for MVP
   testing; devices must instead be pre-provisioned directly in `device_records` (see below). If a
   real pairing UX is needed later, it requires a genuinely new backend feature (out of scope here
   per "do not add ... unrelated features").
2. **No device auto-provisioning.** A physical/emulator device's `DeviceIdProvider`-derived 8-digit
   ID is not known in advance. *Resolution (documented, not code)*: run the app once, capture the
   derived ID from Logcat (tag `WebSocketService`, look for the `X-Device-Id` handshake log or
   `DeviceIdProvider`), then insert/update a row in `device_records` (via `data.sql` or the H2
   console at `http://localhost:8090/h2-console`, JDBC URL `jdbc:h2:mem:frverify`, user `sa`, no
   password — while the backend process is running, since it's in-memory) with that `device_id`,
   `status='ACTIVE'`, and a `branch_id`/`device_nickname` matching what the HTTP caller will send.
3. **`liveness-cancelled` is a no-op on the backend.** If the on-device liveness challenge is
   cancelled *before* the corresponding `liveness-result`/`liveness-finished` message is sent (the
   Android app currently sends both on cancel — see `LivenessActivity.notifyBackendTerminated`),
   the backend still receives a completion signal via `liveness-result` (now correlated correctly,
   §3) so this is **not** actually a stuck flow — it will surface the correct failure. Documented
   here only because the backend's own class-doc comment explicitly calls `liveness-cancelled`
   "out of scope for this MVP"; no code change made given the "smallest necessary change" mandate
   and because the fallback (`liveness-result`) already resolves it correctly.
4. **`liveness-confidence-threshold` (65) appears unused.** Per code inspection, the mock liveness
   service returns `passed`/`score` directly from config or per-request override, without comparing
   `score` against `liveness-confidence-threshold` anywhere found in the reviewed service classes.
   Not an Android-side concern; flagged for backend owners to verify intent.
5. **No multipart test performed against `/api/facial-auth`** from this environment (Windows
   PowerShell 5.1 lacks a convenient multipart client here); `/api/validation` (JSON) was verified
   live (see §9). Source-level review of `RegistrationController`/`RegistrationRequest` gives high
   confidence the same DTO contract holds; recommend a manual Postman/curl multipart check before
   sign-off.

## 9. Testing Status

| Item | Status |
|---|---|
| Backend build (`mvnw.cmd -o compile`) | ✅ `BUILD SUCCESS` |
| Backend startup (`mvnw.cmd -o spring-boot:run`) | ✅ Started on port 8090, H2 seeded, WS endpoint registered |
| `POST /api/validation` live call | ✅ Returns `400` + well-formed `VerificationResponse` (`reason:"Device is not connected."`) when no device is attached — confirms DTO parsing, validation, and error-shape all work as documented |
| `POST /api/facial-auth` live call | ⏳ Not exercised (see Blocker #5) |
| Android compile (`gradlew --offline :app:compileDebugKotlin`) | ✅ `BUILD SUCCESSFUL` (3m 12s, 29 tasks). All warnings emitted are pre-existing (deprecated APIs, unchecked casts elsewhere in the codebase) and unrelated to these changes. |
| Registration end-to-end (device attached) | ⏳ Requires a provisioned device (Blocker #2) — not yet run |
| Verification end-to-end (device attached) | ⏳ Requires a provisioned device (Blocker #2) — not yet run |

## 10. Next Steps

1. Provision a test device row (Blocker #2) and run a real end-to-end registration, then
   verification, from the Android app against the local backend.
2. Manually verify `POST /api/facial-auth` (multipart) via curl/Postman.
3. If physical-device testing is needed, add the dev machine's LAN IP to both
   `FR_SERVER_WS_URL` (via `gradle.properties`) and `network_security_config.xml`.
4. Decide whether AWS Rekognition (`aws.enabled=true`) should be turned on for either side before
   any non-local testing, since the mock services currently drive all match/liveness outcomes.
