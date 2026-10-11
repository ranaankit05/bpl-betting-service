package com.bally.bpl.repository;

import com.bally.bpl.domain.IdempotencyId;
import com.bally.bpl.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, IdempotencyId> {
}
