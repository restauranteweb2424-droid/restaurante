package com.gestorgastronomico.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Una caja: desde que abrió hasta que cerró (o hasta ahora si sigue abierta), con sus ventas. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SesionCajaDTO {
    private LocalDateTime apertura;
    private LocalDateTime cierre;
    private Double total;
    private Integer cantidadVentas;
    /** Mesas y retiro: la plata del local. */
    private Double totalLocal;
    /** Delivery, con su envío: se reparte aparte. */
    private Double totalDelivery;
    private Integer cantidadDelivery;
    private boolean abierta;
    private List<VentaResponseDTO> ventas;

    /** Cantidad y total por medio de pago, solo de esta caja. */
    private java.util.Map<String, java.util.Map<String, Object>> porMetodoPago;

    /** Cambio inicial, retiros y gastos de esta caja. */
    private List<com.gestorgastronomico.entity.MovimientoCaja> movimientos;

    /** Cobros anulados mientras esta caja estaba abierta. */
    private List<com.gestorgastronomico.entity.VentaAnulada> anuladas;

    // Arqueo de efectivo. En una caja abierta, efectivoContado y diferencia van en null.
    private Double fondoInicial;
    private Double ventasEfectivo;
    private Double retiros;
    private Double gastos;
    private Double efectivoEsperado;
    private Double efectivoContado;
    private Double diferencia;
    private String cerradaPor;
}
