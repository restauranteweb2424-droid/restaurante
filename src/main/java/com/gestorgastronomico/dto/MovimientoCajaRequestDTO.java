package com.gestorgastronomico.dto;

import com.gestorgastronomico.entity.MovimientoCaja;
import lombok.Data;

/** Un retiro o un gasto de la caja abierta. */
@Data
public class MovimientoCajaRequestDTO {
    private MovimientoCaja.Tipo tipo;
    private Double monto;
    private String motivo;
}
