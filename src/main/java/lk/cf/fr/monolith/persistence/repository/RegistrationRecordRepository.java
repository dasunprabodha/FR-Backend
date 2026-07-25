package lk.cf.fr.monolith.persistence.repository;

import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegistrationRecordRepository extends JpaRepository<RegistrationRecord, Long> {

    List<RegistrationRecord> findByCifNo(String cifNo);

    List<RegistrationRecord> findByCifNoAndActiveStatus(String cifNo, String activeStatus);

    Optional<RegistrationRecord> findByCifNoAndActionType(String cifNo, String actionType);

    Optional<RegistrationRecord> findByReferenceId(String referenceId);

    List<RegistrationRecord> findByNicOrderByReqTimeDesc(String nic);
}
