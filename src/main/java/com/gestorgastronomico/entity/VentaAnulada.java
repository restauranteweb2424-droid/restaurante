package com.gestorgastronomico.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registro de un cobro anulado. La venta se borra (el pedido vuelve a quedar sin
 * cobrar) y acá queda la copia, con el motivo y quién lo anuló.
 */
@Entity
@Table(name = "ventas_anuladas")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VentaAnulada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ventaId;
    private Long pedidoId;

    @Column(columnDefinition = "DATE")
    private LocalDate fecha;
    private LocalTime hora;
    @Column(columnDefinition = "DATE")
    private LocalDate jornada;

    private Double total;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MetodoPago metodoPago;

    private String nombreCliente;
    @Column(length = 20)
    private String tipoPedido;
    private String mesa;

    @Column(length = 200)
    private String motivo;
    private String anuladaPor;
    private LocalDateTime anuladaEn;
}
