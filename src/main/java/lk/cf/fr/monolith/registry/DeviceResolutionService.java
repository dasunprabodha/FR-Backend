package lk.cf.fr.monolith.registry;

import lk.cf.fr.monolith.registry.entity.DeviceRecord;
import lk.cf.fr.monolith.registry.repository.DeviceRecordRepository;
import lk.cf.fr.monolith.verification.model.VerificationException;
import lk.cf.fr.monolith.verification.model.VerificationState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Direct-call replacement for the device-authorization slice of
 * cf-fr-server Transaction/services/impl/TransactionManagementServiceImpl
 * (resolveDevice + ensureDeviceActiveOrFail) - see VERIFICATION_PATH_ARCHITECTURE.md section
 * 3.2/§8. This is the only place a device's business authorization is checked
 * (status == "ACTIVE").
 */
@Service
@RequiredArgsConstructor
public class DeviceResolutionService {

    private final DeviceRecordRepository deviceRecordRepository;

    /**
     * Resolves (branchId, deviceNickname) -> an ACTIVE device, matching the fallback order used
     * by the legacy {@code resolveDevice}: binding by nickname first, then a userId-based
     * heuristic. Throws if no device is found or the device is not ACTIVE.
     */
    public DeviceRecord resolveActiveDeviceOrFail(String branchId, String deviceNickname, String userId) {
        Optional<DeviceRecord> resolved = resolve(branchId, deviceNickname, userId);

        if (resolved.isEmpty()) {
            throw new VerificationException(VerificationState.FAILED,
                    "Device not found for branchId=" + branchId + " deviceNickname=" + deviceNickname,
                    "Device isn't found or device isn't activated.");
        }

        DeviceRecord device = resolved.get();
        if (device.getStatus() == null || !device.getStatus().equalsIgnoreCase("ACTIVE")) {
            throw new VerificationException(VerificationState.FAILED,
                    "Device " + device.getDeviceId() + " is not ACTIVE (status=" + device.getStatus() + ")",
                    "Device isn't found or device isn't activated.");
        }
        return device;
    }

    private Optional<DeviceRecord> resolve(String branchId, String deviceNickname, String userId) {
        if (branchId != null && !branchId.isBlank() && deviceNickname != null && !deviceNickname.isBlank()) {
            List<DeviceRecord> byNickname = deviceRecordRepository.findActiveByBranchAndNickname(branchId, deviceNickname);
            if (!byNickname.isEmpty()) {
                return Optional.of(byNickname.get(0));
            }
        }
        if (userId != null && !userId.isBlank()) {
            Optional<DeviceRecord> active = deviceRecordRepository
                    .findTopByUserIdAndStatusIgnoreCaseOrderByUpdatedAtDesc(userId, "ACTIVE");
            if (active.isPresent()) {
                return active;
            }
            return deviceRecordRepository.findTopByUserIdOrderByUpdatedAtDesc(userId);
        }
        return Optional.empty();
    }
}
