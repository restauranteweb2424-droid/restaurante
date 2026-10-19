package com.gestorgastronomico.dto;

import lombok.Data;

/** Datos opcionales al abrir o cerrar la caja: el cambio inicial o lo que se contó. */
@Data
public class CajaRequestDTO {
    /** Al abrir: con cuánto efectivo arranca. */
    private Double fondoInicial;
    /** Al cerrar: cuánto efectivo se contó. */
    private Double efectivoContado;
}
