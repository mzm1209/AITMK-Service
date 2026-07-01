package com.example.aitmk.repository;

import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.ResourceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    List<ResourceEntity> findByResourceStatusAndIdGreaterThanOrderByIdAsc(
            ResourceStatus status, Long id, Pageable pageable);

    @Query("select r from ResourceEntity r where r.resourceStatus = :status and r.customerPhone not like :prefix order by r.createdAt asc")
    Optional<ResourceEntity> findFirstByResourceStatusAndCustomerPhoneNotLikeOrderByCreatedAtAsc(
            @Param("status") ResourceStatus status, @Param("prefix") String prefix);

    @Deprecated
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ResourceEntity r where r.customerPhone = :phone")
    Optional<ResourceEntity> findByCustomerPhoneForUpdate(@Param("phone") String phone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ResourceEntity r where r.id=:id")
    Optional<ResourceEntity> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query(value = "INSERT INTO business_resource (customer_phone, source_external_id, created_at, updated_at) "
            + "VALUES (:phone, :phone, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)", nativeQuery = true)
    void upsertByCustomerPhone(@Param("phone") String phone);

    @Query(value = "SELECT LAST_INSERT_ID()", nativeQuery = true)
    Number selectLastInsertId();
}
