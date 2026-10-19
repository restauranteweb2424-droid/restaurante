package com.gestorgastronomico.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Limpieza única de datos viejos de pruebas (ronda 5: M-6 y V-5).
 * Cada sentencia apunta a un dato puntual y es idempotente: después de la
 * primera vez no encuentra nada que cambiar. Si una falla, se registra y sigue.
 * Se puede borrar este archivo en la versión siguiente.
 */
@Component
public class LimpiezaDatosDePrueba {

    private static final Logger log = LoggerFactory.getLogger(LimpiezaDatosDePrueba.class);

    private final JdbcTemplate jdbc;

    public LimpiezaDatosDePrueba(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void limpiar() {
        // M-6: precio con centavos de una carga anterior a la regla de pesos enteros.
        correr("Agua saborizada a $1.500",
                "UPDATE productos SET precio = 1500 WHERE nombre = 'Agua saborizada' AND precio = 1499.99");
        // M-6: plato de ejemplo inactivo (solo si nunca se usó en un pedido).
        correr("Plato de ejemplo borrado",
                "DELETE FROM productos WHERE nombre LIKE 'Plato de ejemplo%' AND activo = false "
                        + "AND NOT EXISTS (SELECT 1 FROM detalle_pedidos d WHERE d.producto_id = productos.id)");

        // V-5: venta de $9.499,99 → $9.500 (y su pedido).
        correr("Venta de $9.499,99 a $9.500",
                "UPDATE ventas SET total = 9500 WHERE total = 9499.99");
        correr("Pedido de $9.499,99 a $9.500",
                "UPDATE pedidos SET total = 9500 WHERE total = 9499.99");
        // Las cajas cerradas que sumaron esos centavos.
        correr("Cajas sin centavos",
                "UPDATE cierres_caja SET total_ventas = ROUND(total_ventas) WHERE total_ventas <> ROUND(total_ventas)");

        // V-5: envío negativo de una prueba vieja.
        correr("Envío negativo a $0",
                "UPDATE pedidos SET costo_envio = 0 WHERE costo_envio < 0");

        // V-5: "Corregido: antes Efectivo" cuando sigue en efectivo (la corrección no cambió nada).
        correr("Correcciones sin cambio",
                "UPDATE ventas SET metodo_pago_original = NULL, corregido_por = NULL, corregido_en = NULL "
                        + "WHERE metodo_pago_original IS NOT NULL AND metodo_pago_original = metodo_pago");
        // Correcciones viejas guardadas con el email: mostrar el nombre.
        correr("Corrección con nombre en vez de email",
                "UPDATE ventas SET corregido_por = (SELECT u.nombre FROM usuarios u WHERE u.email = ventas.corregido_por) "
                        + "WHERE corregido_por LIKE '%@%' AND EXISTS (SELECT 1 FROM usuarios u WHERE u.email = ventas.corregido_por)");

        // V-5 / V-12: cajas vacías del 03/10 y la caja que seguía contando una venta borrada.
        correr("Cajas vacías del 03/10 borradas",
                "DELETE FROM cierres_caja WHERE cantidad_ventas = 0 "
                        + "AND fecha_cierre >= '2026-10-02 00:00:00' AND fecha_cierre < '2026-10-05 00:00:00'");
        correr("Cajas del 02 al 04/10 recalculadas",
                "UPDATE cierres_caja SET "
                        + "total_ventas = (SELECT COALESCE(SUM(v.total), 0) FROM ventas v "
                        + "  WHERE TIMESTAMP(v.fecha, v.hora) >= cierres_caja.fecha_apertura AND TIMESTAMP(v.fecha, v.hora) < cierres_caja.fecha_cierre), "
                        + "cantidad_ventas = (SELECT COUNT(*) FROM ventas v "
                        + "  WHERE TIMESTAMP(v.fecha, v.hora) >= cierres_caja.fecha_apertura AND TIMESTAMP(v.fecha, v.hora) < cierres_caja.fecha_cierre) "
                        + "WHERE fecha_apertura IS NOT NULL "
                        + "AND fecha_cierre >= '2026-10-02 00:00:00' AND fecha_cierre < '2026-10-05 00:00:00'");
    }

    private void correr(String que, String sql) {
        try {
            int filas = jdbc.update(sql);
            if (filas > 0) log.info("LimpiezaDatosDePrueba: {} ({} fila/s).", que, filas);
        } catch (Exception e) {
            log.warn("LimpiezaDatosDePrueba: no se pudo hacer «{}»: {}", que, e.getMessage());
        }
    }
}
