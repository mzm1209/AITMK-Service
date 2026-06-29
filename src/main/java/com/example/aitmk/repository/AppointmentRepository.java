package com.example.aitmk.repository;

import com.example.aitmk.model.entity.AppointmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentRepository extends JpaRepository<AppointmentEntity, Long> {

    List<AppointmentEntity> findByResourceIdOrderByAppointmentTimeDesc(Long resourceId);

    List<AppointmentEntity> findByResourceIdAndStatusOrderByAppointmentTimeDesc(Long resourceId, String status);
}
