# Registration Pipeline Review — Findings & Recommendations

Review-only deliverable. No code was changed to produce this document. Scope: the registration
pipeline (device capture → card crop → OCR → face comparison → liveness → decision → approval →
S3 upload) and the verification pipeline that consumes its output, as implemented today in
`lk.cf.fr.monolith.*`.

Grounding: every finding below cites the actual class/behavior observed in this codebase (not
generic advice). Where a finding was already known/flagged during earlier work this session
(e.g. the plaintext AWS credentials, zero test coverage), it's restated here for completeness
since this review is meant to be a standalone, prioritized reference.

---

## 1. Prioritized Recommendations

### Critical — should block/precede any production deployment

#### C1. Real AWS credentials committed in plaintext in `application.yml`
- **Where**: `aws.access-key` / `aws.secret-key` / `aws.session-token` in `application.yml` — a
  real STS session token and secret, currently tracked in git.
- **Why it matters**: `AwsClientsConfig`'s own class doc explicitly says *"the legacy
  application.yml had a real AWS key committed in plaintext, which must NOT be carried
  forward"* — the current config violates the exact principle the code documents. Anyone with
  repo access (or a leaked clone/backup) has live AWS credentials scoped to Rekognition/S3.
- **Expected improvement**: Eliminates a live credential-leak vector.
- **Complexity**: Low (move to env vars / secrets manager; SDK default credential chain is
  already wired as the fallback in `AwsClientsConfig`).
- **Risk of implementing**: None — purely subtractive.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes — immediately, and rotate the exposed credentials regardless of whether
  this repo is ever pushed to a shared remote, since STS tokens can be reused until they expire.

#### C2. No authentication/authorization anywhere
- **Where**: Every controller (`RegistrationController`, `VerificationController`,
  `ApprovalController`, `RegistrationRecordController`) is `permitAll`-equivalent by omission —
  documented repeatedly as "explicit MVP scope," but that scope needs an expiry date before prod.
- **Why it matters**: Anyone who can reach the API can register/verify/approve/reject
  registrations and read back captured NIC/face images (`GET /api/approval/{id}/image/{type}`
  has zero access control). `reviewedBy` on approve/reject is a free-text field the caller
  supplies themselves — there's no way to know who *actually* clicked approve.
- **Expected improvement**: Closes the largest single risk surface in the system.
- **Complexity**: Medium-High (needs a real auth model — this affects every controller and the
  approval audit trail's meaning).
- **Risk**: Medium — touches every request path; needs careful testing once added.
- **Affects existing registrations**: No (additive).
- **Requires retraining**: No.
- **Worth doing**: Yes, before any real customer data flows through this. Already designed for
  it — `ApprovalActionRequest.reviewedBy` is deliberately a placeholder for "populate from the
  security context later" (see `RegistrationApprovalService` class doc).

#### C3. Zero automated test coverage
- **Where**: `find src/test` returns nothing — no `src/test` directory exists at all in this
  project.
- **Why it matters**: This session alone added/modified NIC OCR regex logic, the
  similarity+liveness pass/fail gate, approval state transitions, S3 upload idempotency, and an
  ONNX detection pipeline — all by hand, all unverified except by manual curl/log tracing. Any
  future change (including implementing the recommendations below) has no safety net.
- **Expected improvement**: Regressions get caught before they reach a real registration.
- **Complexity**: Medium to start (unit tests for `RekognitionDocumentProcessingService`'s regex
  ladder, `RegistrationApprovalService`'s state machine, and `CardDetectorService`'s crop math
  are all pure-logic and cheap to test in isolation); higher for integration tests that need a
  mock device WebSocket client.
- **Risk**: None — additive.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes — start with the pure-logic pieces (NIC OCR classification, approval
  transitions, comparison gate) since they're the highest-risk, cheapest-to-test surface.

#### C4. No upload size/type limits
- **Where**: `RegistrationController.register` accepts `scannedNIC` as an unbounded
  `MultipartFile`; nothing validates size, dimensions, or content-type before it's handed to OCR
  and (now) `CardDetectorService`'s 5-strategy inference pipeline.
- **Why it matters**: A large or malformed upload can trigger expensive OpenCV/ONNX work (each
  `cropCard` call now does up to 5 sequential 640×640 inferences) or exhaust memory decoding a
  huge image. This is a real, cheaply-exploitable resource-exhaustion vector, not theoretical.
- **Expected improvement**: Bounds worst-case request cost.
- **Complexity**: Low (Spring's `multipart.max-file-size` + an explicit dimension check before
  decode).
- **Risk**: Low.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes, and cheap enough to do alongside C1.

#### C5. `CardDetectorService` availability is invisible
- **Where**: `CardDetectorService.isAvailable()` exists but nothing calls it outside the class
  itself. If ONNX model loading fails at startup (bad path, corrupt file, missing native libs on
  a given deployment platform), every `cropCard` call silently returns `null` forever, and
  `RegistrationService` silently falls back to the pre-crop behavior — with only a single
  `ERROR` log line at startup to notice it by.
- **Why it matters**: This is exactly the kind of failure that goes unnoticed for weeks: nothing
  in the registration response, the approval dashboard, or any metric indicates "card cropping
  is currently disabled." The system keeps working, just less accurately, silently.
- **Expected improvement**: Turns a silent degradation into a visible, alertable signal.
- **Complexity**: Low (expose via a health indicator + log a warning per-request, not just at
  startup, when falling back).
- **Risk**: None.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes — low effort, closes a real blind spot in exactly the feature just built.

#### C6. No retention/cleanup policy for locally stored PII images
- **Where**: `LocalPendingImageStorageService`, `ComparisonImageDumpService`, and
  `CardDetectorService`'s card-crop dumps all write NIC photos and face images to local disk
  under `./data/**` with no expiry, no encryption at rest, and no cleanup job. Approved/rejected
  records keep their images forever (by design, for audit) — but there's no policy stating for
  *how long*, and the comparison-analysis/card-crop dumps (pure diagnostics) accumulate
  indefinitely with no audit justification at all.
- **Why it matters**: This is government-ID-photo + biometric-face data sitting unencrypted on
  local disk indefinitely. Depending on jurisdiction (Sri Lanka's Personal Data Protection Act,
  for instance) this is a real compliance exposure, independent of the access-control gap in C2.
- **Expected improvement**: Bounds data exposure and brings the system in line with a defensible
  retention policy.
- **Complexity**: Medium (retention policy needs a business decision first, then a scheduled
  cleanup job; encryption-at-rest is an infra decision — disk-level or per-file).
- **Risk**: Medium — deleting the wrong thing (e.g. images still needed for an active approval)
  would be bad; needs to be scoped carefully to diagnostics-only data first.
- **Affects existing registrations**: Only if retroactively applied to old data.
- **Requires retraining**: No.
- **Worth doing**: Yes, at least for the pure-diagnostic `comparison-analysis` and card-crop
  dumps — those have no audit reason to be permanent and are the safest to start pruning.

---

### High Value — significant accuracy/reliability/production-readiness gains

#### H1. `CardDetectorService` latency is high and untuned
- **Where**: `detectMultiStrategy` runs 5 sequential inference passes per image (original,
  CLAHE, sharpen, 0.75×, 1.5×). Measured directly this session: ~3.8s for one image's full pass
  on this dev machine's CPU. Registration now calls this twice (nicImage + selfImage) — a
  measured ~7-8s of pure CPU inference added on top of the existing device round-trips.
- **Reason**: Every strategy runs unconditionally even when the first (original) pass already
  finds a high-confidence detection.
- **Expected improvement**: Early-exit once a detection clears a high-confidence bar (e.g.
  >0.7) would likely cut typical-case latency by 60-80%, keeping the expensive multi-strategy
  fallback only for genuinely hard cases.
- **Complexity**: Low (short-circuit after the "original" pass if `bestScore` is high enough).
- **Risk**: Low — purely a performance change to an already-best-effort path.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes, soon — this is the single biggest new latency cost introduced this
  session and directly affects registration turnaround time for every user.

#### H2. Unverified `NUM_CLASSES=1` vs. 6-channel model output
- **Where**: `CardDetectorService.decodeYoloOutput` logged `dim1=6` for `card_model_2.onnx`
  during the standalone smoke test, but `NUM_CLASSES=1` only ever reads channel index 4,
  ignoring index 5 entirely.
- **Reason**: A standard single-class YOLOv8 export has 5 channels (4 box + 1 class score); 6
  suggests either a second trained class or an extra channel whose meaning isn't confirmed.
- **Expected improvement**: Confirms (or fixes) whether the detector is silently discarding
  useful model output.
- **Complexity**: Low to check (test against a real NIC photo and inspect logged scores per
  channel), potentially Low-Medium to fix if channel 5 turns out to matter.
- **Risk**: Low to check; unknown until verified.
- **Affects existing registrations**: No.
- **Requires retraining**: No — this is a decode-logic question, not a model question, unless
  the investigation reveals the model needs a different export.
- **Worth doing**: Yes — first real-device registration should specifically check this in the
  logs before trusting the detector's accuracy numbers.

#### H3. OCR still runs on the raw, uncropped `scannedNIC` upload
- **Where**: `RegistrationService.validateScannedNic` calls
  `documentProcessingService.validateNic(scannedNicBytes, ...)` directly — `scannedNicBytes`
  never passes through `CardDetectorService`, even though the card cropper now exists in this
  same codebase.
- **Reason**: This is precisely the accuracy gap `REGISTRATION_IMPLEMENTATION_PROGRESS.md`
  Blocker #1 predicted when it excluded the card detector originally: *"OCR runs on the full
  captured photo instead of a pre-cropped card region... may reduce OCR accuracy on
  cluttered/angled photos."* That prediction is still true today for the OCR path specifically —
  the card detector was only wired into the face-comparison path (nicImage/selfImage), not OCR.
- **Expected improvement**: Tighter, deskewed-ish input to Rekognition `DetectText` should
  reduce OCR noise (stray background text, glare regions) exactly like it now does for face
  comparison.
- **Complexity**: Low (route `scannedNicBytes` through `cardDetectorService.cropCard(...,
  "ScannedNIC", ...)` before OCR, with the same null-fallback pattern already used for
  nicImage/selfImage).
- **Risk**: Low — same fallback-to-original-bytes safety net already proven in this session's
  integration.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes — natural, low-cost follow-up to the work already done, and directly
  closes a gap this project's own docs already predicted.

#### H4. No image-quality gating before comparison/OCR
- **Where**: Nowhere in the pipeline is blur, exposure, or glare assessed. A blurry or
  glare-washed capture goes straight into Rekognition CompareFaces/DetectText and just produces
  a low similarity score or `UNKNOWN` OCR result — indistinguishable from "this genuinely isn't
  a match" in the response the user sees.
- **Reason**: `overallSimilarityDecision`/`failureReason` today only ever say
  `LOW_SIMILARITY`/`LIVENESS_FAILED` — a user whose photo was simply too dark gets the same
  message as someone who's a genuine mismatch, and has no actionable feedback to retake a better
  photo.
- **Expected improvement**: Fewer wasted registration attempts, better user-facing guidance
  ("photo too dark, please retake" vs. a generic failure), likely fewer false PENDING_APPROVAL
  cases caused by capture quality rather than genuine mismatch.
- **Complexity**: Medium (blur via Laplacian variance, exposure via histogram analysis — both
  cheap with the OpenCV dependency already added for `CardDetectorService`; glare is harder,
  reflective-highlight detection is a reasonable heuristic but not exact).
- **Risk**: Low-Medium — needs careful threshold tuning to avoid rejecting legitimate photos.
- **Affects existing registrations**: No.
- **Requires retraining**: No (classical CV metrics, not a model).
- **Worth doing**: Yes, phased — start with blur (cheapest, highest signal) before glare/shadow
  detection.

#### H5. No duplicate-identity detection
- **Where**: `RegistrationResultService.createOrSupersede` only dedupes by `cifNo` — it
  supersedes a prior registration for the *same* `cifNo`, but never checks whether the *face*
  being registered already exists under a *different* `cifNo`/NIC.
- **Reason**: This is a fraud-prevention gap: nothing stops the same person from enrolling twice
  under two different identities, or from someone else's face photo being paired with a
  different NIC number (assuming the card-vs-face comparisons happen to still pass some other
  way, e.g. via a low bar or in PENDING_APPROVAL that later gets manually approved).
- **Expected improvement**: Closes a real identity-fraud vector inherent to any enrollment
  system.
- **Complexity**: Medium-High (needs a 1:N face search across all enrolled `FaceOnly/*.jpg`
  images, which Rekognition supports via a Face Collection + `SearchFacesByImage`, but that's a
  different AWS integration than the 1:1 `CompareFaces` used today).
- **Risk**: Medium — false positives here (flagging two different people as duplicates) would
  be user-facing and need a manual-review path (which the approval workflow already provides a
  natural home for).
- **Affects existing registrations**: No (new registrations only, unless backfilled).
- **Requires retraining**: No, but requires provisioning a Rekognition Collection.
- **Worth doing**: Yes, but scope it for a dedicated phase — it's a meaningfully bigger feature
  than everything else in this list.

#### H6. `reviewedBy` has no real identity behind it
- **Where**: `ApprovalActionRequest.reviewedBy` — accepted as free text from the request body.
- **Reason**: Already a known, documented limitation (the class doc explicitly frames it as "a
  placeholder until auth exists"), but worth restating here because it directly undermines the
  audit trail the Approval Workflow is otherwise doing right (timestamps, remarks, status
  history are all solid — the identity behind them isn't).
- **Expected improvement**: Makes the approval history trustworthy as an actual audit log.
- **Complexity**: Low once C2 (auth) exists — this becomes "read from `SecurityContext` instead
  of the request body," not a new design.
- **Risk**: Low.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes, bundled with C2 — don't build it standalone, since it's meaningless
  without real auth behind it.

#### H7. No observability (health, metrics, alerting)
- **Where**: `pom.xml` has no `spring-boot-starter-actuator`; there is no `/health`,
  `/metrics`, or structured event stream anywhere. The only signal today is log lines.
- **Reason**: Nobody would know today if: Rekognition calls started failing, the card detector's
  false-negative rate spiked, S3 uploads started failing, or the approval queue started backing
  up — short of manually grepping logs.
- **Expected improvement**: Turns "grep the logs after a complaint" into "get paged before
  users notice."
- **Complexity**: Low to start (actuator health/info endpoints), Medium for real metrics
  (comparison score distributions, approval/rejection rates, card-detection success rate,
  per-step latency) via Micrometer.
- **Risk**: Low.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes — actuator alone is a half-day addition with outsized value before any
  production traffic.

#### H8. No database migration tooling
- **Where**: `spring.jpa.hibernate.ddl-auto: update`, no Flyway/Liquibase anywhere, despite an
  Oracle JDBC driver present in `pom.xml` clearly anticipating a real prod schema.
- **Reason**: `ddl-auto: update` against a live Oracle schema is exactly the kind of thing that
  silently does the wrong thing under a schema it doesn't fully understand (added columns
  this session alone: `similarity_threshold`, `liveness_threshold`, `failure_reason`,
  `reviewed_by`, `remarks`, `images_uploaded_to_s3` — all auto-applied via `ddl-auto`, with no
  record of *how* they were applied to any given environment).
- **Expected improvement**: Reproducible, reviewable, rollback-capable schema changes instead of
  "whatever Hibernate inferred this time."
- **Complexity**: Medium (introducing Flyway/Liquibase to an app that's been running on
  `ddl-auto` requires a baseline migration capturing current state first).
- **Risk**: Medium — the migration-adoption step itself needs care to not conflict with
  whatever's already been auto-applied to a given environment.
- **Affects existing registrations**: No, if done carefully (baseline-first).
- **Requires retraining**: No.
- **Worth doing**: Yes, before this ever points at a real Oracle instance.

#### H9. Hardcoded/under-tunable thresholds
- **Where**: `CardDetectorService.CONF_THRESH = 0.35f` (a Java constant, not even a `@Value`) —
  the class's own comment says *"keep this low first... later use 0.35f or 0.45f for stricter
  production cropping"* — i.e. it's explicitly labeled as a debug-time value never revisited.
  Contrast with `verification.similarity-threshold`/`liveness-confidence-threshold`, which are
  at least externalized to `application.yml`.
- **Reason**: A threshold that can only be changed by editing Java source and redeploying is
  much harder to tune against real production data than a config value.
- **Expected improvement**: Enables data-driven threshold tuning without a code change/redeploy
  cycle.
- **Complexity**: Low (convert to `@Value` with the current value as default, same pattern
  already used everywhere else in this codebase).
- **Risk**: Low.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes — trivial effort, and directly unblocks H2's tuning work.

#### H10. No retry/backoff on transient AWS failures
- **Where**: Every Rekognition call (`compareFacesInMemory` ×4, `DetectText`, liveness
  create/get) is a single attempt — one transient network blip anywhere in that chain fails the
  entire registration, forcing the user to redo the full device-capture flow from scratch.
- **Reason**: AWS SDKs have built-in retry support that isn't being configured/leveraged beyond
  its defaults; a registration involves *up to 7 sequential AWS API calls* (4 compares + OCR +
  liveness create + liveness get), each a single point of failure today.
- **Expected improvement**: Fewer user-facing failures caused by transient network/service
  issues rather than genuine mismatches.
- **Complexity**: Low-Medium (AWS SDK v2 has configurable retry policies; needs care to only
  retry idempotent-safe calls and not double-charge/double-call on ambiguous failures).
- **Risk**: Low-Medium — retries add latency on the failure path; needs sane backoff caps.
- **Affects existing registrations**: No.
- **Requires retraining**: No.
- **Worth doing**: Yes, especially given how expensive a full redo is for the user (device
  capture + liveness challenge, not just an API call).

---

### Nice-to-Have — real value, but lower urgency or speculative ROI

| # | Recommendation | Why | Effort | Notes |
|---|---|---|---|---|
| N1 | Perspective correction / deskew before crop | `cropCard` returns an axis-aligned rect only; a tilted card crops with background still included on two sides | Medium | Needs the rotated-box variant of the detector or a separate deskew pass (e.g. Hough-line based) |
| N2 | Approval Dashboard: side-by-side viewer, score visualization, comparison heatmaps | Genuinely useful for reviewers, but frontend-only, no backend blocker | Medium | Natural next iteration of the Approval Dashboard already built |
| N3 | Weighted/ensemble multi-image face scoring | Marginal accuracy gain over today's independent 4-comparison gate | Medium-High | Needs real accuracy data to justify before investing |
| N4 | Extra anti-spoofing beyond AWS's built-in liveness | AWS Rekognition Face Liveness already does spoof/replay/screen detection; a second layer is speculative unless AWS's is measured as insufficient | High | Don't build until AWS's liveness is proven inadequate in practice |
| N5 | Additional decision states (Image Quality Failure, OCR Failure, Duplicate Candidate, Technical Failure) | More precise `failureReason` values than today's `LOW_SIMILARITY`/`LIVENESS_FAILED` | Low-Medium | Natural byproduct of implementing H4/H5 — don't build the states before the checks that would populate them |
| N6 | ONNX session/model warm-up at startup | First inference after boot pays extra JIT/native warm-up cost | Low | Minor - a background warm-up call in `@PostConstruct` |
| N7 | API versioning (`/api/v1/...`) | No breaking-change safety net today | Low | Cheap to add now, expensive to retrofit later - worth doing early even though it's "nice to have" |
| N8 | Rate limiting | No protection against a misbehaving client hammering the API | Low-Medium | Pairs naturally with C2/C4 |

---

## 2. Area-by-Area Findings (mapped to your 14 sections)

**1. Card Detection** — See H1 (latency), H2 (channel-count verification), N1 (perspective
correction/rotated boxes). Confidence threshold is already tunable in principle (H9 makes it
actually tunable). Retry strategy already exists in a sense (5-strategy multi-pass *is* the
retry mechanism) — the issue is cost, not absence, of retries. Multiple NIC layouts: the model
is single-class (`id_card`) and layout-agnostic by design, so this should generalize without
code changes — worth confirming with a variety of real card photos (old/new NIC format, as
covered in the OCR conversation) but shouldn't need new logic. Glare/shadow/motion-blur handling
specifically isn't addressed by the detector today — falls under H4's proposed general
image-quality gate rather than card-detection-specific logic.

**2. Image Quality Assessment** — See H4. No metrics exist today. Recommend starting with blur
(Laplacian variance, cheap and high-signal) and resolution/size checks (already partially
covered by C4's size limits), before investing in glare/shadow which are harder to get reliable
thresholds for.

**3. OCR Pipeline** — See H3 (the card-crop integration gap) as the highest-value single change.
Beyond that: no deskew/contrast/denoise preprocessing happens before `DetectText` today (relies
entirely on Rekognition's own internal handling); no multi-pass or confidence-based retry exists
(`RekognitionDocumentProcessingService` is single-pass). Checksum/field validation: Sri Lankan
NIC numbers don't have a public checksum digit in the same way e.g. a credit card does, so this
is likely not applicable — worth confirming against the actual NIC number format if this is a
requirement, but nothing in the current classification logic assumes one.

**4. Face Detection** — Handled entirely inside Rekognition's `CompareFaces` today (no separate
local face-detection step). Multiple-face handling: `CompareFacesResponse` already exposes
`unmatchedFaces()` for extra faces in the target image, but nothing in
`RekognitionFaceRecognitionService` currently inspects or logs that list — worth surfacing for
diagnostics, similar to how bounding boxes were surfaced for the comparison-dump feature this
session. Alignment/pose/occlusion/mask/sunglasses detection are all things AWS Rekognition's
`DetectFaces` API can report (`Pose`, `Quality`, `Sunglasses`, `EyesOpen`, etc.) but this
codebase never calls `DetectFaces` at all — only `CompareFaces`/`DetectText`. Adding a
`DetectFaces` call specifically to surface these quality attributes would be a natural sibling
to H4's broader image-quality work.

**5. Face Comparison** — Score normalization: not needed today since there's one comparison
engine (Rekognition), consistent scale. Weighted multi-image comparison and ensemble scoring:
see N3 — the four-comparison gate already effectively does *some* of this (cmp2/cmp3/cmp4 must
all pass), but each is a hard boolean AND, not a weighted/blended score. Confidence estimation:
Rekognition's similarity score already functions as this.

**6. Liveness Detection** — Fully delegated to AWS Rekognition Face Liveness
(`RekognitionLivenessService`), which already does passive liveness + spoof/replay/screen-attack
detection server-side per AWS's own documentation. Building a second, custom liveness layer on
top would be largely redundant unless AWS's is measured as insufficient in practice (see N4).
Confidence calibration: `MockLivenessService`'s scoring (`passed ? defaultScore :
min(defaultScore, 40.0)`) is demo-only and doesn't affect the real path. Infrared: not
applicable without different capture hardware.

**7. Decision Logic** — Current three-state model (Approved/Pending/Rejected, with
`AWS_APPROVED` as the internal auto-pass variant) is clean and already has a `failureReason`
field for texture. Recommend against adding new top-level *statuses* until H4/H5 are actually
implemented — the additional states you listed (Image Quality Failure, OCR Failure, Duplicate
Candidate) are natural `failureReason` values *once those checks exist*, not new states to bolt
on speculatively now (see N5).

**8. Performance** — See H1 (card-detector latency, the single biggest new cost) and H10
(retry/resilience). ONNX session is created once at startup and reused (correct); OpenCV `Mat`
objects are consistently `.release()`'d in the ported code (correct resource hygiene, verified
by reading through every method). No parallelization exists between the nicImage/selfImage crop
calls or the 4 face comparisons — they run sequentially; given AWS API calls are I/O-bound,
parallelizing the 4 `compareFacesInMemory` calls (currently sequential) with
`CompletableFuture`/an executor could meaningfully cut wall-clock time, independent of H1's
ONNX-specific latency fix.

**9. Error Handling** — Generally solid where I looked closely: `RegistrationService`'s
try/catch/finally correctly rolls back on hard failures (`removeIncompleteRecord` +
`localPendingImageStorageService.deleteAll`), `GlobalExceptionHandler` correctly maps domain
exceptions to HTTP statuses (after this session's fix to stop `ResponseStatusException` being
swallowed by the generic handler). Gaps: no retry (H10), and S3 upload failures during
auto-pass are deliberately swallowed-and-logged (by design, documented in
`S3FaceImageStorageService`'s class doc) — worth re-confirming that's still the desired behavior
now that the approval-path's *own* S3 failure handling is stricter (leaves the record
`PENDING_APPROVAL` rather than silently continuing) - the two paths currently have deliberately
different philosophies and that asymmetry should be a documented decision, not an oversight.

**10. Security** — See C1 (credentials), C2 (auth), C4 (upload limits), C6 (retention). Path
traversal: `LocalPendingImageStorageService`/`ComparisonImageDumpService`/`CardDetectorService`
all construct file paths from `referenceId` (always a server-generated UUID) and `tag` (always a
hardcoded string literal in calling code, never user input) — **not currently exploitable**, but
flagging as a latent risk if any tag value is ever derived from request input in the future.
Malicious uploads: no content-type/magic-byte verification on `scannedNIC` beyond what
`ImageIO`/OpenCV's decoders tolerate (both fail gracefully on non-image bytes, so this degrades
to "OCR fails" rather than a crash, but a dedicated check would be more explicit).

**11. Approval Workflow** — Already has solid bones from this session's work: audit fields
(`reviewedBy`, `remarks`, `actionDate`), idempotency guard against duplicate approve/reject
(409 Conflict), image preview endpoints. Gaps against your list: no reviewer-identity-backed
audit (H6), no rollback capability (an `APPROVED` record can't be un-approved/re-queued today —
worth considering whether that's actually desired, since S3 images would already be uploaded by
that point), no comparison-score visualization/heatmaps in the dashboard UI (N2, frontend work).
Duplicate approvals are already prevented (409 on a non-`PENDING_APPROVAL` record).

**12. Verification Pipeline** — Currently 1:1 only (`compareFaces` against a single
`FaceOnly/<nic>.jpg`), no card-crop involved (verification doesn't capture a NIC image at all,
only a live face — so H3's card-crop-before-OCR concern doesn't apply here). Retry logic: same
gap as H10, applies equally to verification's Rekognition calls. Candidate filtering: N/A today
since it's 1:1 by `cifNo`, not a search — this would only become relevant if H5's duplicate-face
search work extends into verification-time lookup too.

**13. Maintainability** — The codebase is unusually well-documented for an MVP (every class has
a class-doc explaining *why*, not just *what*, and traces back to a legacy-architecture
document). Duplication is low — `FaceRecognitionService`/`LivenessService` are already correctly
shared between registration and verification per the codebase's own stated design principle.
One real complexity concern: `RegistrationService.runRegistration` has grown to ~110 lines
across this session's additions (card-crop, comparison-dump, approval-workflow persistence) —
still readable, but a natural candidate to extract the "crop nicImage/selfImage" block and the
"run all 4 comparisons + dump" block into named private methods (or a small
`RegistrationComparisonService`) if more steps get added on top. Testability: see C3 — the
biggest maintainability gap isn't structure, it's the total absence of tests to make future
refactors safe.

**14. Production Readiness** — See H7 (observability), H8 (migrations), C1/C2/C4/C6 (security),
N7/N8 (versioning/rate-limiting). No backup/disaster-recovery story exists for the H2 dev
database (expected, it's in-memory) or for the local-disk image storage (`./data/**`) — if this
ever runs against real Oracle + real local storage, both need an actual backup policy, not just
"whatever the OS/disk provides."

---

## 3. Architectural Weaknesses / Technical Debt Summary

- **Security posture is MVP-only by explicit, repeated design choice** (C1, C2) — every
  controller's class doc says so outright. This isn't accidental debt, it's a known deferral
  that now has a growing pile of features built on top of it (approval workflow, image
  retrieval) that will all need retrofitting once auth lands.
- **No safety net for changes** (C3) — every improvement in this document, including ones
  already implemented this session, has been verified by hand (manual curl testing, log
  tracing, a standalone smoke-test harness) rather than a repeatable test suite. This is the
  single biggest structural risk for the pipeline's long-term maintainability.
- **Local-disk storage has grown organically** — three separate services now write to `./data/**`
  (`LocalPendingImageStorageService`, `ComparisonImageDumpService`, and `CardDetectorService`'s
  crop dumps) with no shared retention/lifecycle policy between them. Worth unifying into one
  policy (even if the storage mechanism stays split) before this grows further.
- **Two different AWS-failure philosophies coexist** — the auto-pass path swallows S3 upload
  failures (by design), the manual-approval path treats them as blocking (also by design,
  added this session). This divergence is currently correct-by-intent but should be written
  down as a deliberate decision somewhere visible (this doc, or a class-level note) so it
  doesn't read as an inconsistency to a future maintainer.
- **The ONNX/OpenCV dependency reintroduces exactly the weight the project originally excluded
  it to avoid** — not a mistake (the model file justifies it), but worth tracking as a
  deliberate trade-off: larger artifact, longer cold start, new failure mode (C5) that didn't
  exist before.

---

## 4. Recommended Roadmap (impact vs. effort)

**Phase 0 — Do now, low effort, high/critical impact**
1. C1 — rotate + externalize AWS credentials
2. C4 — upload size/type limits
3. H9 — externalize `CardDetectorService` thresholds to config
4. H1 — early-exit card-detector multi-strategy loop
5. C5 — surface `CardDetectorService.isAvailable()` as a health signal + per-request warning
6. H3 — route `scannedNicBytes` through the card cropper before OCR
7. H2 — verify the 6-channel model output against a real NIC photo

**Phase 1 — Foundational, before real production traffic**
8. C3 — test coverage, starting with pure-logic pieces (OCR classification, approval state
   machine, comparison gate)
9. C2 — authentication/authorization
10. H6 — real `reviewedBy` identity (bundled with #9)
11. H7 — actuator health/metrics
12. H8 — Flyway/Liquibase baseline

**Phase 2 — Accuracy/reliability once real usage data exists**
13. H4 — image-quality gating (blur first)
14. H10 — retry/backoff on AWS calls
15. C6 — retention policy for local image storage (start with diagnostics-only data)

**Phase 3 — Larger investments, justify with real data first**
16. H5 — duplicate-identity detection (Rekognition Face Collection)
17. N1/N2/N3 — perspective correction, dashboard visualization, ensemble scoring
18. N4 — additional anti-spoofing (only if AWS's liveness proves insufficient in practice)

No code has been changed as part of this review. Let me know which items you'd like to move
forward with and I'll scope each one individually before touching anything.
