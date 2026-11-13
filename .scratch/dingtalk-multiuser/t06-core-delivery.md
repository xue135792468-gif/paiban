# 06 Notification Core 施工交付

Status: core implemented and targeted green; whole ticket still awaits merged API/UI/isolation QA and independent dual-axis review.

## Base and commits

- Review base: `23b789f` (05 integration release).
- Starting documentation tip: `0979597`.
- Core checkpoint: `02565816723287b46c82e7415410b36452a037aa`.
- Recipient/business test increment: `05f7dd6f0107980a37120a825d778d90a5424708`.
- Integration merge used before final targeted run: `1dd5bcc`; merge commit in this worktree: `c813287`.

## Implemented boundary

- Business transactions create immutable `NotificationDelivery` snapshots with rendered title/content/link and semantic `USER` or `TEACHER` targets. No JPA entity, mutable entity ID state or SecurityContext crosses the transaction boundary.
- `NotificationDeliveryBridge` listens with `AFTER_COMMIT` and `fallbackExecution=true`; it only submits work to the dedicated bounded `notificationExecutor`.
- `NotificationDeliveryGateway` runs external delivery through a proxy annotated `NOT_SUPPORTED`, keeping DingTalk network I/O outside business and ambient transactions.
- Queue rejection, unexpected worker exceptions and ordinary failures produce fixed safe delivery records. No notification infrastructure failure changes an already committed approval response.
- Delivery re-reads mapping and enabled state. Approval results use the real operator account; substitute/move notifications use the final Teacher. Multiple accounts with the same DingTalk UID are deduplicated, disabled-only accounts are suppressed, and a trusted Teacher without an AppUser can receive DingTalk without creating an account.
- The dedicated notification executor uses one worker and a bounded queue capacity of 200. Persistence outbox/process-crash replay is not introduced.
- The composite channel retains ordered first-success degradation. A DingTalk success stops fallback; failure permits local delivery when enabled local users exist.
- A failed DingTalk attempt uses at most three total attempts. Successful and failed attempts retain existing `notification_log` evidence.
- Active triggers are narrowed to approval approved/rejected, final substitute Teacher effective, and each moved Teacher's own swap details. Original-teacher, head-teacher, administrator/recipient-list, timetable-publish and verification-alert triggers are retired; legacy templates and setting values remain registered.
- `DatabaseResetExtension` waits up to 10 seconds for the notification executor queue and active task to drain. A missing executor bean or timeout fails reset explicitly; unexpected runtime errors are no longer swallowed.

## Acceptance evidence

All logs are retained in this worktree under `.runtime/` and must not be treated as source-controlled evidence:

- `.runtime/t06-red.log`: initial red tracer. The approval returned but no post-commit DingTalk delivery began after 10 seconds; one test failed.
- `.runtime/t06-checkpoint-green.log`: requested checkpoint pair, 2 tests passed, Maven exit 0.
- `.runtime/t06-disabled-green.log`: disabled-account/no-local-success regression, 1 test passed, Maven exit 0.
- `.runtime/t06-business-direct-swap.log`: direct-cover final-Teacher detail and per-Teacher swap details, 2 tests passed, Maven exit 0.
- `.runtime/t06-business-retired.log`: direct cover, swap and timetable-publish retirement, 3 tests passed, Maven exit 0.
- `.runtime/t06-operator-final-green.log`: delegated operator/expected/final recipients remain distinct, 1 test passed, Maven exit 0.
- `.runtime/t06-existing-regression-premerge.log`: 05 approval, direct substitution, swap and publish regressions, 41 tests passed, Maven exit 0.
- `.runtime/t06-postmerge-targeted.log`: post-merge transport, notification core, business and publish set, 23 tests passed, Maven exit 0.

The public HTTP/DB tests cover:

- approval HTTP completes while blocked DingTalk I/O is still in flight; approval/effect state is committed and independently readable before delivery logs appear;
- approved/rejected content and exact operator recipient;
- expected Teacher and final Teacher differ; only the final Teacher receives the cover message;
- direct cover and workbench swap positive paths;
- disabled-only, multi-account/same-UID, no-Account+UID, enabled-local+no-UID and no-Account+no-UID boundaries;
- exactly three DingTalk attempts followed by local fallback;
- rolled-back business notification dispatch sends nothing;
- timetable publish and retired public template produce no DingTalk call or delivery rows.

The merged transport test `DingTalkWorkNotificationHttpTest` also passed 8/8 under a local HTTP stub, including official `action_card` request shape and numeric `errcode=0` handling. It is a wire-contract test, not tenant or phone-delivery proof.

## Not validated here

- No full `verify.sh`, E2E, Docker/PostgreSQL, deployment, push or real DingTalk tenant/network run was performed.
- Actual mobile receipt, task processing and read state require the school's tenant acceptance.
- Queue-full and notification-log database failure isolation are assigned to the independent `NotificationDeliveryIsolationIntegrationTest` QA worker.
- Admin delivery-log API and frontend behavior are owned by separate workers. This core worker did not modify `notification/web/**`, `NotificationDeliveryLogQueryService.java`, `NotificationDeliveryLogApiIntegrationTest.java`, `HttpDingTalkClient.java` or frontend files.
