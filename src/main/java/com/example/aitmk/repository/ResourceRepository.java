package com.example.aitmk.repository;

import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.ResourceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<ResourceEntity, Long> {
    Optional<ResourceEntity> findByCustomerPhone(String customerPhone);
    List<ResourceEntity> findAllByLastMessageAtIsNotNull(Sort sort);
    @Query("select r.id from ResourceEntity r")
    List<Long> findAllIds();
    Optional<ResourceEntity> findFirstByResourceStatusOrderByCreatedAtAsc(ResourceStatus status);

    /** Find first PENDING resource whose phone does NOT start with prefix (for non-whitelist agents). */
    @Query("select r from ResourceEntity r where r.resourceStatus = :status and r.customerPhone not like :prefix order by r.createdAt asc")
    Optional<ResourceEntity> findFirstByResourceStatusAndCustomerPhoneNotLikeOrderByCreatedAtAsc(
            @Param("status") ResourceStatus status, @Param("prefix") String prefix);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ResourceEntity r where r.customerPhone = :phone")
    Optional<ResourceEntity> findByCustomerPhoneForUpdate(@Param("phone") String phone);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ResourceEntity r where r.id=:id")
    Optional<ResourceEntity> findByIdForUpdate(@Param("id") Long id);
}
