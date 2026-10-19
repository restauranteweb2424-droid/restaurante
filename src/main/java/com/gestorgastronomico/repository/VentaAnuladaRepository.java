package com.gestorgastronomico.repository;

import com.gestorgastronomico.entity.VentaAnulada;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface VentaAnuladaRepository extends JpaRepository<VentaAnulada, Long> {

    List<VentaAnulada> findByAnuladaEnBetweenOrderByAnuladaEnAsc(LocalDateTime desde, LocalDateTime hasta);

    List<VentaAnulada> findByJornadaBetweenOrderByAnuladaEnAsc(java.time.LocalDate desde, java.time.LocalDate hasta);
}
