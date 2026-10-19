package com.gestorgastronomico.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Plata que entra o sale de la caja sin ser una venta: el cambio con que arranca
 * (FONDO), lo que se saca (RETIRO) y lo que se paga (GASTO). Pertenece a la caja
 * que estaba abierta en ese momento.
 */
@Entity
@Table(name = "movimientos_caja")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovimientoCaja {

    public enum Tipo { FONDO, RETIRO, GASTO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fechaHora;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Tipo tipo;

    @Column(nullable = false)
    private Double monto;

    @Column(length = 120)
    private String motivo;

    private String usuario;
}
