package com.lovehibachi.aichatbot.repository;

import com.lovehibachi.aichatbot.domain.HandoffLink;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import javax.persistence.LockModeType;

public interface HandoffLinkRepository extends JpaRepository<HandoffLink, String> {
    Optional<HandoffLink> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select link from HandoffLink link where link.tokenHash = :tokenHash")
    Optional<HandoffLink> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    long deleteByExpiresAtBefore(Instant cutoff);
}
