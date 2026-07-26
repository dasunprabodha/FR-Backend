-- Demo seed data for the verification MVP.
--
-- The real cf-fr-server system populates DEVICE_RECORDS via a device-pairing/activation flow
-- and REGISTRATION_RECORD via the enrollment flow, both of which are out of scope for this
-- verification-only MVP (see IMPLEMENTATION_PROGRESS.md "Temporary assumptions"). These two
-- rows exist purely so POST /api/validation has something to resolve against out of the box.

INSERT INTO device_records (user_id, device_id, app_instance_id, status, branch_id, device_nickname, model, os_version, created_at, updated_at)
VALUES ('dasun', '88806537', 'APP-DEMO-0001', 'ACTIVE', 'BR001', 'DemoDevice', 'Pixel-Demo', '14', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO registration_record (nic, user_id, cif_no, reference_id, action_type, status, active_status, req_time)
VALUES ('199012345678', 'DEMO-USER-001', 'CIF1001', 'seed-registration-0001', 'REGISTRATION', 'APPROVED', 'ACTIVE', CURRENT_TIMESTAMP);
