-- Demo seed data for the verification MVP.
--
-- The real cf-fr-server system populates DEVICE_RECORDS via a device-pairing/activation flow
-- and REGISTRATION_RECORD via the enrollment flow, both of which are out of scope for this
-- verification-only MVP (see IMPLEMENTATION_PROGRESS.md "Temporary assumptions"). These two
-- rows exist purely so POST /api/validation has something to resolve against out of the box.

-- MERGE (not INSERT) so this stays safe to run on every startup (spring.sql.init.mode: always)
-- now that the datasource is a persistent file (jdbc:h2:file:) instead of a throwaway in-memory
-- one - a plain INSERT would violate the unique constraint on device_id/reference_id on the
-- second-and-later restart and crash the app. Matched rows get their timestamp columns refreshed
-- to "now" on every restart - harmless for demo seed data, not something real registration/
-- approval rows go through (those are only ever created once, via the actual API).
MERGE INTO device_records (user_id, device_id, app_instance_id, status, branch_id, device_nickname, model, os_version, created_at, updated_at)
KEY (device_id)
VALUES ('dasun', '88806537', 'APP-DEMO-0001', 'ACTIVE', 'BR001', 'DemoDevice', 'Pixel-Demo', '14', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

MERGE INTO registration_record (nic, user_id, cif_no, reference_id, action_type, status, active_status, req_time, images_uploaded_to_s3)
KEY (reference_id)
VALUES ('199012345678', 'DEMO-USER-001', 'CIF1001', 'seed-registration-0001', 'REGISTRATION', 'APPROVED', 'ACTIVE', CURRENT_TIMESTAMP, TRUE);
