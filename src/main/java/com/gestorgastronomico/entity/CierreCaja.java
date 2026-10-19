package com.gestorgastronomico.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Historial de cajas cerradas: una fila por cada cierre. */
@Entity
@Table(name = "cierres_caja")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CierreCaja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime fechaApertura;

    private LocalDateTime fechaCierre;

    private Double totalVentas;

    private Integer cantidadVentas;

    /** Arqueo: lo que debería haber en efectivo (cambio + ventas en efectivo − retiros − gastos). */
    private Double efectivoEsperado;

    /** Lo que se contó al cerrar. Null si no se contó. */
    private Double efectivoContado;

    /** Contado − esperado: negativo si falta plata. */
    private Double diferencia;

    private String cerradaPor;
}
