package lk.cf.fr.monolith.persistence.repository;

import lk.cf.fr.monolith.persistence.entity.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, Long> {

    Optional<TransactionRecord> findByReferenceId(String referenceId);
}
