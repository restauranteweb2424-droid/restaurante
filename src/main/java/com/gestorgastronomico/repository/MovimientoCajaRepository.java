package com.gestorgastronomico.repository;

import com.gestorgastronomico.entity.MovimientoCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MovimientoCajaRepository extends JpaRepository<MovimientoCaja, Long> {

    List<MovimientoCaja> findByFechaHoraBetweenOrderByFechaHoraAsc(LocalDateTime desde, LocalDateTime hasta);
}
