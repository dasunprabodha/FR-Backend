package lk.cf.fr.monolith.registry.repository;

import lk.cf.fr.monolith.registry.entity.DeviceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Mirrors the resolution fallback chain in cf-fr-server
 * Transaction/services/impl/TransactionManagementServiceImpl.resolveDevice(). The Oracle-native
 * binding-table join (BRANCH_DEVICE_BINDING) is out of scope for this MVP - only the direct-column
 * fallback on DEVICE_RECORDS is ported, since that is sufficient for the verification path.
 */
public interface DeviceRecordRepository extends JpaRepository<DeviceRecord, Long> {

    Optional<DeviceRecord> findByDeviceId(String deviceId);

    Optional<DeviceRecord> findByAppInstanceId(String appInstanceId);

    @Query("""
        SELECT d FROM DeviceRecord d
         WHERE d.branchId = :branchId
           AND UPPER(d.deviceNickname) = UPPER(:nickname)
           AND UPPER(d.status) = 'ACTIVE'
         ORDER BY d.updatedAt DESC
    """)
    List<DeviceRecord> findActiveByBranchAndNickname(@Param("branchId") String branchId,
                                                       @Param("nickname") String nickname);

    Optional<DeviceRecord> findTopByUserIdAndStatusIgnoreCaseOrderByUpdatedAtDesc(String userId, String status);

    Optional<DeviceRecord> findTopByUserIdOrderByUpdatedAtDesc(String userId);
}
