package com.gestorgastronomico.service;

import com.gestorgastronomico.dto.SesionCajaDTO;
import com.gestorgastronomico.dto.VentaRequestDTO;
import com.gestorgastronomico.dto.VentaResponseDTO;
import com.gestorgastronomico.entity.*;
import com.gestorgastronomico.exception.BusinessException;
import com.gestorgastronomico.exception.ResourceNotFoundException;
import com.gestorgastronomico.repository.CierreCajaRepository;
import com.gestorgastronomico.repository.MovimientoCajaRepository;
import com.gestorgastronomico.repository.VentaAnuladaRepository;
import com.gestorgastronomico.repository.PedidoRepository;
import com.gestorgastronomico.repository.UsuarioRepository;
import com.gestorgastronomico.repository.VentaRepository;
import com.gestorgastronomico.websocket.RealtimeNotifier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class VentaService {

    private static final Logger logger = LoggerFactory.getLogger(VentaService.class);
    /** Una caja abierta más de este tiempo se considera olvidada: las ventas cuentan en su día real. */
    private static final int DIAS_HACIA_ATRAS_DE_UNA_CAJA = 31;
    private static final int HORAS_MAXIMAS_DE_UNA_JORNADA = 24;
    /** Tope para el cambio, los retiros, los gastos y lo contado en la caja. */
    private static final double MONTO_MAXIMO_CAJA = 10_000_000;

    private final VentaRepository ventaRepository;
    private final PedidoRepository pedidoRepository;
    private final ConfigLocalService configLocalService;
    private final RealtimeNotifier realtimeNotifier;
    private final CierreCajaRepository cierreCajaRepository;
    private final UsuarioRepository usuarioRepository;
    private final MovimientoCajaRepository movimientoCajaRepository;
    private final VentaAnuladaRepository ventaAnuladaRepository;
    private final Clock clock;

    /**
     * Registra el cobro de un pedido. Con entregar = true (lo normal) el pedido
     * pasa a ENTREGADO; con false queda pagado y sigue su curso en cocina.
     */
    public VentaResponseDTO registrar(VentaRequestDTO dto) {
        if (Boolean.FALSE.equals(configLocalService.obtener().getCajaAbierta())) {
            throw new BusinessException("La caja está cerrada. Abrila antes de cobrar.");
        }
        Pedido pedido = pedidoRepository.findById(dto.getPedidoId())
                .orElseThrow(() -> new ResourceNotFoundException("Pedido", dto.getPedidoId()));
        if (pedido.getEstado() == EstadoPedido.ENTREGADO || pedido.getEstado() == EstadoPedido.CANCELADO) {
            throw new BusinessException("No se puede cobrar un pedido " + pedido.getEstado().name().toLowerCase() + ".");
        }
        if (pedido.estaPagado() || ventaRepository.findByPedidoId(pedido.getId()).isPresent()) {
            throw new BusinessException("Este pedido ya está cobrado.");
        }

        LocalDateTime ahora = LocalDateTime.now(clock);
        Venta venta = Venta.builder()
                .fecha(ahora.toLocalDate())
                .hora(ahora.toLocalTime())
                .jornada(jornadaActual())
                .total(pedido.getTotal())
                .metodoPago(dto.getMetodoPago())
                .pedido(pedido)
                .cobradoPor(nombreDe(SecurityContextHolder.getContext().getAuthentication()))
                .build();
        pedido.setVenta(venta);

        if (pedido.esComandaPorTarjetas()) {
            // La mesa se cierra sola cuando además de cobrada está todo entregado.
            ComandaMesa.recalcularEstado(pedido, ahora);
        } else if (!Boolean.FALSE.equals(dto.getEntregar())) {
            pedido.setEstado(EstadoPedido.ENTREGADO);
            pedido.setEntregadoEn(ahora);
        }
        Venta guardada = ventaRepository.save(venta);
        pedidoRepository.save(pedido);
        realtimeNotifier.avisarPedidos();
        return toDTO(guardada);
    }

    /**
     * Corrige el medio de pago de un cobro mal cargado. El admin puede corregir
     * cualquier venta; el cajero, solo las de la caja abierta. Queda registrado
     * el medio original, quién lo cambió y cuándo.
     */
    public VentaResponseDTO corregirMetodoPago(Long ventaId, MetodoPago nuevo) {
        if (nuevo == null) throw new BusinessException("Elegí el medio de pago correcto.");
        Venta venta = ventaRepository.findById(ventaId).orElseThrow(() -> new ResourceNotFoundException("Venta", ventaId));
        if (venta.getMetodoPago() == nuevo) return toDTO(venta);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!esAdmin(auth) && !perteneceACajaAbierta(venta)) {
            throw new BusinessException("Solo se pueden corregir cobros de la caja abierta. Para uno anterior, pedíselo al admin.");
        }

        if (nuevo == MetodoPago.TARJETA && venta.getPedido() != null && venta.getPedido().getTipo() == TipoPedido.DELIVERY) {
            throw new BusinessException("En delivery no se cobra con tarjeta.");
        }

        if (venta.getMetodoPagoOriginal() == null) venta.setMetodoPagoOriginal(venta.getMetodoPago());
        venta.setMetodoPago(nuevo);
        if (nuevo == venta.getMetodoPagoOriginal()) {
            // Volvió al medio con que se cobró: ya no hay nada corregido.
            venta.setMetodoPagoOriginal(null);
            venta.setCorregidoPor(null);
            venta.setCorregidoEn(null);
        } else {
            venta.setCorregidoPor(nombreDe(auth));
            venta.setCorregidoEn(LocalDateTime.now(clock));
        }
        logger.info("Venta #{}: medio de pago corregido a {} por {}", ventaId, nuevo, venta.getCorregidoPor());
        Venta guardada = ventaRepository.save(venta);
        realtimeNotifier.avisarPedidos();
        return toDTO(guardada);
    }

    private String nombreDe(Authentication auth) {
        if (auth == null) return null;
        return usuarioRepository.findByEmail(auth.getName())
                .map(u -> u.getNombre() != null && !u.getNombre().isBlank() ? u.getNombre() : u.getEmail())
                .orElse(auth.getName());
    }

    private boolean perteneceACajaAbierta(Venta venta) {
        if (Boolean.FALSE.equals(configLocalService.obtener().getCajaAbierta())) return false;
        LocalDateTime apertura = inicioCajaActual();
        LocalDateTime momento = venta.getFecha().atTime(venta.getHora());
        return apertura == null ? venta.getFecha().equals(LocalDate.now(clock)) : !momento.isBefore(apertura);
    }

    @Transactional(readOnly = true)
    public List<VentaResponseDTO> listarTodas() {
        return ventaRepository.findAll().stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public List<VentaResponseDTO> listarPorFecha(LocalDate jornada) {
        return ventaRepository.findByJornada(jornada).stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public Double totalDelDia(LocalDate jornada) {
        return ventaRepository.sumTotalByJornada(jornada);
    }

    @Transactional(readOnly = true)
    public VentaResponseDTO obtenerPorId(Long id) {
        return toDTO(ventaRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Venta", id)));
    }

    /** Informe de un rango de jornadas: totales, envíos aparte, medios de pago y ranking. */
    @Transactional(readOnly = true)
    public Map<String, Object> informe(LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null) throw new BusinessException("Indicá las fechas del informe");
        if (hasta.isBefore(desde)) throw new BusinessException("La fecha final no puede ser anterior a la inicial");

        List<Venta> ventas = ventaRepository.findEntreJornadas(desde, hasta);
        double total = ventas.stream().mapToDouble(VentaService::totalDe).sum();
        double envios = ventas.stream().mapToDouble(VentaService::envioDe).sum();
        List<Venta> deDelivery = ventas.stream().filter(VentaService::esDelivery).toList();
        double totalDelivery = deDelivery.stream().mapToDouble(VentaService::totalDe).sum();

        Map<String, Map<String, Object>> porPago = porMetodo(ventas);

        List<Map<String, Object>> productos = new ArrayList<>();
        for (Object[] fila : ventaRepository.rankingProductosPorJornada(desde, hasta)) {
            Map<String, Object> producto = new HashMap<>();
            producto.put("nombre", fila[0]);
            producto.put("categoria", fila[1]);
            producto.put("variante", fila[2]);
            producto.put("unidades", fila[3] != null ? ((Number) fila[3]).longValue() : 0L);
            producto.put("ingreso", fila[4] != null ? ((Number) fila[4]).doubleValue() : 0.0);
            productos.add(producto);
        }

        Map<String, Object> informe = new HashMap<>();
        informe.put("desde", desde.toString());
        informe.put("hasta", hasta.toString());
        informe.put("cantidadVentas", ventas.size());
        informe.put("total", total);
        informe.put("envios", envios);
        informe.put("totalSinEnvios", total - envios);
        informe.put("totalLocal", total - totalDelivery);
        informe.put("totalDelivery", totalDelivery);
        informe.put("cantidadDelivery", deDelivery.size());
        informe.put("ticketPromedio", ventas.isEmpty() ? 0.0 : (total - envios) / ventas.size());
        informe.put("porMetodoPago", porPago);
        informe.put("productos", productos);
        informe.put("ventas", ventas.stream().map(this::toDTO).toList());
        informe.put("anuladas", ventaAnuladaRepository.findByJornadaBetweenOrderByAnuladaEnAsc(desde, hasta));
        informe.put("cancelacionesConEntregados", cancelacionesEntre(
                desde.atTime(LocalTime.of(6, 0)), hasta.plusDays(1).atTime(LocalTime.of(6, 0))));
        return informe;
    }

    /** Mesas canceladas con platos entregados en un rango de tiempo (para la caja y el informe). */
    private List<Map<String, Object>> cancelacionesEntre(LocalDateTime desde, LocalDateTime hasta) {
        List<Map<String, Object>> cancelaciones = new ArrayList<>();
        for (Pedido p : pedidoRepository.findByEntregadoAlCancelarIsNotNullAndCanceladoEnBetweenOrderByCanceladoEnAsc(desde, hasta)) {
            Map<String, Object> c = new HashMap<>();
            c.put("pedidoId", p.getId());
            c.put("mesa", p.getMesa());
            c.put("motivo", p.getMotivoCancelacion());
            c.put("canceladoPor", p.getCanceladoPor());
            c.put("canceladoEn", p.getCanceladoEn() != null ? p.getCanceladoEn().toString() : null);
            c.put("entregado", p.getEntregadoAlCancelar());
            cancelaciones.add(c);
        }
        return cancelaciones;
    }

    @Transactional(readOnly = true)
    public List<VentaResponseDTO> listarDesdeCierre() {
        LocalDateTime inicio = inicioCajaActual();
        if (inicio == null) return listarPorFecha(LocalDate.now(clock));
        return ventaRepository.findDesde(inicio.toLocalDate(), inicio.toLocalTime()).stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public Double totalDesdeCierre() {
        return listarDesdeCierre().stream().mapToDouble(v -> v.getTotal() != null ? v.getTotal() : 0.0).sum();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> estadoCaja() {
        ConfigLocal cfg = configLocalService.obtener();
        Map<String, Object> estado = new HashMap<>();
        boolean abierta = !Boolean.FALSE.equals(cfg.getCajaAbierta());
        estado.put("abiertaDesde", cfg.getCierreCaja());
        estado.put("abierta", abierta);
        estado.put("jornadaActual", jornadaActual().toString());
        if (abierta) {
            LocalDateTime desde = inicioCajaActual();
            if (desde == null) desde = LocalDate.now(clock).atStartOfDay();
            List<Venta> ventas = ventasDesde(desde);
            List<MovimientoCaja> movs = movimientoCajaRepository
                    .findByFechaHoraBetweenOrderByFechaHoraAsc(desde, LocalDateTime.now(clock).plusMinutes(1));
            estado.putAll(arqueo(ventas, movs));
            estado.put("movimientos", movs);
        }
        return estado;
    }

    public Map<String, Object> cerrarCaja(Double efectivoContado) {
        ConfigLocal cfg = configLocalService.obtener();
        if (Boolean.FALSE.equals(cfg.getCajaAbierta())) {
            throw new BusinessException("La caja ya está cerrada.");
        }
        Double contado = montoDeCaja(efectivoContado, "El efectivo contado");
        List<VentaResponseDTO> ventas = listarDesdeCierre();
        double total = ventas.stream().mapToDouble(v -> v.getTotal() != null ? v.getTotal() : 0.0).sum();
        LocalDateTime ahora = LocalDateTime.now(clock).withNano(0);
        LocalDateTime apertura = inicioCajaActual();
        LocalDateTime desde = apertura != null ? apertura : ahora.toLocalDate().atStartOfDay();

        Map<String, Double> arqueo = arqueo(ventasDesde(desde),
                movimientoCajaRepository.findByFechaHoraBetweenOrderByFechaHoraAsc(desde, ahora.plusSeconds(1)));
        double esperado = arqueo.get("efectivoEsperado");
        Double diferencia = contado != null ? contado - esperado : null;
        String quien = nombreDe(SecurityContextHolder.getContext().getAuthentication());

        cierreCajaRepository.save(CierreCaja.builder()
                .fechaApertura(desde)
                .fechaCierre(ahora)
                .totalVentas(total)
                .cantidadVentas(ventas.size())
                .efectivoEsperado(esperado)
                .efectivoContado(contado)
                .diferencia(diferencia)
                .cerradaPor(quien)
                .build());

        cfg.setCierreCaja(ahora.toString());
        cfg.setCajaAbierta(false);
        configLocalService.guardar(cfg);

        Map<String, Object> resumen = new HashMap<>();
        resumen.put("cerradaEn", ahora.toString());
        resumen.put("totalCerrado", total);
        resumen.put("cantidadVentas", ventas.size());
        double delivery = ventas.stream().filter(v -> "DELIVERY".equals(v.getTipoPedido()))
                .mapToDouble(v -> v.getTotal() != null ? v.getTotal() : 0.0).sum();
        resumen.put("totalDelivery", delivery);
        resumen.put("totalLocal", total - delivery);
        resumen.putAll(arqueo);
        resumen.put("efectivoContado", contado);
        resumen.put("diferencia", diferencia);
        return resumen;
    }

    public Map<String, Object> cerrarCaja() {
        return cerrarCaja(null);
    }

    /** Ventas cobradas desde un momento (las de la caja que abrió en ese momento). */
    private List<Venta> ventasDesde(LocalDateTime desde) {
        return ventaRepository.findDesde(desde.toLocalDate(), desde.toLocalTime());
    }

    /**
     * La caja nueva empieza ahora: las ventas anteriores quedan en la caja que se
     * cerró. Si se indica, el cambio inicial queda anotado para el arqueo.
     */
    public void abrirCaja(Double fondoInicial) {
        ConfigLocal cfg = configLocalService.obtener();
        if (!Boolean.FALSE.equals(cfg.getCajaAbierta())) {
            throw new BusinessException("La caja ya está abierta.");
        }
        Double fondo = montoDeCaja(fondoInicial, "El cambio inicial");
        LocalDateTime ahora = LocalDateTime.now(clock).withNano(0);
        cfg.setCierreCaja(ahora.toString());
        cfg.setCajaAbierta(true);
        configLocalService.guardar(cfg);
        if (fondo != null && fondo > 0) {
            movimientoCajaRepository.save(MovimientoCaja.builder()
                    .fechaHora(ahora)
                    .tipo(MovimientoCaja.Tipo.FONDO)
                    .monto(fondo)
                    .motivo("Cambio inicial")
                    .usuario(nombreDe(SecurityContextHolder.getContext().getAuthentication()))
                    .build());
        }
    }

    public void abrirCaja() {
        abrirCaja(null);
    }

    /** Una línea por caja cerrada (las más nuevas primero), para la lista de Cajas anteriores. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> cajasCerradas(int limite) {
        int cantidad = Math.max(1, Math.min(limite, 100));
        return cierreCajaRepository.findByFechaCierreIsNotNullOrderByFechaAperturaDesc(
                        org.springframework.data.domain.PageRequest.of(0, cantidad)).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("apertura", c.getFechaApertura());
                    m.put("cierre", c.getFechaCierre());
                    m.put("total", c.getTotalVentas());
                    m.put("cantidadVentas", c.getCantidadVentas());
                    m.put("diferencia", c.getDiferencia());
                    m.put("cerradaPor", c.getCerradaPor());
                    return m;
                })
                .toList();
    }

    /**
     * Cajas que tuvieron movimiento en una jornada, de la más vieja a la más nueva.
     * Cada caja trae todas sus ventas, aunque algunas sean de otro día (cuando la
     * caja quedó abierta más de un día). Si hay ventas de la jornada que no caen en
     * ninguna caja (datos de versiones anteriores), van en una tarjeta aparte.
     */
    @Transactional(readOnly = true)
    public List<SesionCajaDTO> sesionesDe(LocalDate jornada) {
        LocalDateTime ahora = LocalDateTime.now(clock);
        LocalDate desdeDia = jornada.minusDays(DIAS_HACIA_ATRAS_DE_UNA_CAJA);
        // Una sola consulta para todo el rango; después se reparte por caja en memoria.
        List<Venta> candidatas = ventaRepository.findEntreJornadas(desdeDia.minusDays(1), ahora.toLocalDate().plusDays(1));

        LocalDateTime finVentana = ahora.plusDays(1);
        List<MovimientoCaja> movimientos = movimientoCajaRepository
                .findByFechaHoraBetweenOrderByFechaHoraAsc(desdeDia.atStartOfDay(), finVentana);
        List<VentaAnulada> anuladas = ventaAnuladaRepository
                .findByAnuladaEnBetweenOrderByAnuladaEnAsc(desdeDia.atStartOfDay(), finVentana);

        List<SesionCajaDTO> cajas = new ArrayList<>();
        cierreCajaRepository.findByFechaAperturaBetweenOrderByFechaAperturaAsc(
                        desdeDia.atStartOfDay(), jornada.atTime(LocalTime.MAX))
                .forEach(c -> {
                    if (c.getFechaApertura() == null || c.getFechaCierre() == null) return;
                    List<Venta> ventas = ventasEntre(candidatas, c.getFechaApertura(), c.getFechaCierre());
                    // Va si abrió ese día, si sus ventas cuentan para ese día o si cobró algo ese día.
                    if (c.getFechaApertura().toLocalDate().equals(jornada) || tieneVentasDe(ventas, jornada)
                            || cobroEseDia(ventas, jornada)) {
                        SesionCajaDTO s = sesion(c.getFechaApertura(), c.getFechaCierre(), ventas, false,
                                movimientosEntre(movimientos, c.getFechaApertura(), c.getFechaCierre()),
                                anuladasEntre(anuladas, c.getFechaApertura(), c.getFechaCierre()));
                        s.setEfectivoContado(c.getEfectivoContado());
                        s.setDiferencia(c.getDiferencia());
                        s.setCerradaPor(c.getCerradaPor());
                        s.setCancelacionesConEntregados(cancelacionesEntre(c.getFechaApertura(), c.getFechaCierre()));
                        if (c.getEfectivoEsperado() != null) s.setEfectivoEsperado(c.getEfectivoEsperado());
                        cajas.add(s);
                    }
                });

        boolean abierta = !Boolean.FALSE.equals(configLocalService.obtener().getCajaAbierta());
        if (abierta) {
            LocalDateTime inicio = inicioCajaActual();
            LocalDateTime desde = inicio != null ? inicio : ahora.toLocalDate().atStartOfDay();
            List<Venta> ventas = ventasEntre(candidatas, desde, null);
            if (jornada.isEqual(jornadaActual()) || desde.toLocalDate().equals(jornada) || tieneVentasDe(ventas, jornada)
                    || cobroEseDia(ventas, jornada)) {
                SesionCajaDTO actual = sesion(desde, null, ventas, true,
                        movimientosEntre(movimientos, desde, null), anuladasEntre(anuladas, desde, null));
                actual.setCancelacionesConEntregados(cancelacionesEntre(desde, finVentana));
                cajas.add(actual);
            }
        }

        Set<Long> enCajas = cajas.stream().flatMap(c -> c.getVentas().stream()).map(VentaResponseDTO::getId)
                .collect(Collectors.toSet());
        List<Venta> sueltas = ventaRepository.findByJornada(jornada).stream()
                .filter(v -> !enCajas.contains(v.getId()))
                .toList();
        if (!sueltas.isEmpty()) {
            cajas.add(sesion(null, null, sueltas, false, List.of(), List.of()));
        }
        return cajas;
    }

    private SesionCajaDTO sesion(LocalDateTime apertura, LocalDateTime cierre, List<Venta> ventas, boolean abierta,
                                 List<MovimientoCaja> movimientos, List<VentaAnulada> anuladas) {
        List<VentaResponseDTO> dtos = ventas.stream()
                .sorted(Comparator.comparing(Venta::getFecha).thenComparing(Venta::getHora))
                .map(this::toDTO)
                .toList();
        double total = ventas.stream().mapToDouble(VentaService::totalDe).sum();
        List<Venta> deDelivery = ventas.stream().filter(VentaService::esDelivery).toList();
        double totalDelivery = deDelivery.stream().mapToDouble(VentaService::totalDe).sum();
        Map<String, Double> arqueo = arqueo(ventas, movimientos);
        return SesionCajaDTO.builder()
                .porMetodoPago(porMetodo(ventas))
                .movimientos(movimientos)
                .anuladas(anuladas)
                .fondoInicial(arqueo.get("fondoInicial"))
                .ventasEfectivo(arqueo.get("ventasEfectivo"))
                .retiros(arqueo.get("retiros"))
                .gastos(arqueo.get("gastos"))
                .efectivoEsperado(arqueo.get("efectivoEsperado"))
                .apertura(apertura)
                .cierre(cierre)
                .total(total)
                .cantidadVentas(ventas.size())
                .totalLocal(total - totalDelivery)
                .totalDelivery(totalDelivery)
                .cantidadDelivery(deDelivery.size())
                .abierta(abierta)
                .ventas(dtos)
                .build();
    }

    /** Ventas de [desde, hasta): una caja va desde que abre hasta que cierra. Sin "hasta", hasta ahora. */
    private static List<Venta> ventasEntre(List<Venta> candidatas, LocalDateTime desde, LocalDateTime hasta) {
        return candidatas.stream()
                .filter(v -> v.getFecha() != null && v.getHora() != null)
                .filter(v -> {
                    LocalDateTime momento = v.getFecha().atTime(v.getHora());
                    return !momento.isBefore(desde) && (hasta == null || momento.isBefore(hasta));
                })
                .toList();
    }

    private static boolean tieneVentasDe(List<Venta> ventas, LocalDate jornada) {
        return ventas.stream().anyMatch(v -> jornada.equals(v.getJornada()));
    }

    /** Si la caja cobró algo ese día de calendario (aunque cuente para otra jornada). */
    private static boolean cobroEseDia(List<Venta> ventas, LocalDate dia) {
        return ventas.stream().anyMatch(v -> dia.equals(v.getFecha()));
    }

    private static Map<String, Map<String, Object>> porMetodo(List<Venta> ventas) {
        Map<String, Map<String, Object>> porPago = new LinkedHashMap<>();
        for (MetodoPago metodo : MetodoPago.values()) {
            List<Venta> delMetodo = ventas.stream().filter(v -> v.getMetodoPago() == metodo).toList();
            Map<String, Object> resumen = new HashMap<>();
            resumen.put("cantidad", delMetodo.size());
            resumen.put("total", delMetodo.stream().mapToDouble(VentaService::totalDe).sum());
            porPago.put(metodo.name(), resumen);
        }
        return porPago;
    }

    /** Día de trabajo en curso: la fecha en que se abrió la caja actual. */
    public LocalDate jornadaActual() {
        return calcularJornada(LocalDateTime.now(clock), inicioCajaActual());
    }

    /**
     * Una venta cuenta para el día en que se abrió su caja, salvo que la caja
     * lleve más de 24 horas abierta (alguien se olvidó de cerrarla).
     */
    public LocalDate calcularJornada(LocalDateTime momentoVenta, LocalDateTime aperturaCaja) {
        if (aperturaCaja != null
                && !momentoVenta.isBefore(aperturaCaja)
                && momentoVenta.isBefore(aperturaCaja.plusHours(HORAS_MAXIMAS_DE_UNA_JORNADA))) {
            return aperturaCaja.toLocalDate();
        }
        return momentoVenta.toLocalDate();
    }

    private LocalDateTime inicioCajaActual() {
        String valor = configLocalService.obtener().getCierreCaja();
        if (valor == null || valor.isBlank()) return null;
        try {
            return LocalDateTime.parse(valor);
        } catch (Exception e) {
            logger.warn("Inicio de caja ilegible: {}", valor);
            return null;
        }
    }

    /** Borra las ventas de una jornada. Los pedidos entregados vuelven a LISTO para poder cobrarse de nuevo. */
    public void eliminarPorFecha(LocalDate jornada) {
        List<Venta> ventas = ventaRepository.findByJornada(jornada);
        ventas.forEach(this::liberarPedido);
        ventaRepository.deleteAll(ventas);
        realtimeNotifier.avisarPedidos();
    }

    /** Anula un cobro sin motivo escrito (ruta vieja, solo admin). */
    public void eliminarPorId(Long id) {
        anular(id, "Anulado por el admin");
    }

    /**
     * Anula un cobro: el pedido vuelve a quedar sin cobrar (si estaba entregado,
     * vuelve a LISTO) y la venta queda registrada como anulada, con el motivo y
     * quién la anuló. El admin puede anular cualquier cobro; el cajero, solo los
     * de la caja abierta.
     */
    public void anular(Long ventaId, String motivo) {
        String texto = motivo == null ? "" : motivo.trim();
        if (texto.length() < 3) throw new BusinessException("Escribí el motivo de la anulación.");
        if (texto.length() > 200) throw new BusinessException("El motivo no puede superar los 200 caracteres.");
        Venta venta = ventaRepository.findById(ventaId).orElseThrow(() -> new ResourceNotFoundException("Venta", ventaId));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!esAdmin(auth) && !perteneceACajaAbierta(venta)) {
            throw new BusinessException("Solo se pueden anular cobros de la caja abierta. Para uno anterior, pedíselo al admin.");
        }

        VentaResponseDTO copia = toDTO(venta);
        ventaAnuladaRepository.save(VentaAnulada.builder()
                .ventaId(venta.getId())
                .pedidoId(copia.getPedidoId())
                .fecha(venta.getFecha())
                .hora(venta.getHora())
                .jornada(venta.getJornada())
                .total(venta.getTotal())
                .metodoPago(venta.getMetodoPago())
                .nombreCliente(copia.getNombreCliente())
                .tipoPedido(copia.getTipoPedido())
                .mesa(copia.getMesa())
                .motivo(texto)
                .anuladaPor(nombreDe(auth))
                .anuladaEn(LocalDateTime.now(clock).withNano(0))
                .build());
        liberarPedido(venta);
        ventaRepository.delete(venta);
        logger.info("Venta #{} anulada por {}: {}", ventaId, nombreDe(auth), texto);
        realtimeNotifier.avisarPedidos();
    }

    private static boolean esAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** Un retiro de plata o un gasto pagado con la caja abierta. */
    public MovimientoCaja registrarMovimiento(com.gestorgastronomico.dto.MovimientoCajaRequestDTO dto) {
        if (Boolean.FALSE.equals(configLocalService.obtener().getCajaAbierta())) {
            throw new BusinessException("La caja está cerrada.");
        }
        if (dto == null || dto.getTipo() == null || dto.getTipo() == MovimientoCaja.Tipo.FONDO) {
            throw new BusinessException("Elegí si es un retiro o un gasto.");
        }
        Double monto = montoDeCaja(dto.getMonto(), "El monto");
        if (monto == null || monto <= 0) throw new BusinessException("El monto tiene que ser mayor a cero.");
        String motivo = dto.getMotivo() == null ? "" : dto.getMotivo().trim();
        if (motivo.length() < 3) throw new BusinessException("Escribí para qué es (por ejemplo: pago al repartidor).");
        if (motivo.length() > 120) throw new BusinessException("El detalle no puede superar los 120 caracteres.");
        MovimientoCaja mov = movimientoCajaRepository.save(MovimientoCaja.builder()
                .fechaHora(LocalDateTime.now(clock).withNano(0))
                .tipo(dto.getTipo())
                .monto(monto)
                .motivo(motivo)
                .usuario(nombreDe(SecurityContextHolder.getContext().getAuthentication()))
                .build());
        realtimeNotifier.avisarPedidos();
        return mov;
    }

    /** Monto en pesos enteros, de 0 al tope. Null queda en null. */
    private static Double montoDeCaja(Double monto, String nombre) {
        if (monto == null) return null;
        if (monto.isNaN() || monto < 0) throw new BusinessException(nombre + " no puede ser negativo.");
        if (monto > MONTO_MAXIMO_CAJA) throw new BusinessException(nombre + " no puede superar $10.000.000.");
        if (monto != Math.floor(monto)) throw new BusinessException(nombre + " va en pesos enteros, sin centavos.");
        return monto;
    }

    /** Arqueo de efectivo de una caja: [desde, hasta), sin "hasta" es hasta ahora. */
    private Map<String, Double> arqueo(List<Venta> ventasDeLaCaja, List<MovimientoCaja> movimientosDeLaCaja) {
        double fondo = sumaDe(movimientosDeLaCaja, MovimientoCaja.Tipo.FONDO);
        double retiros = sumaDe(movimientosDeLaCaja, MovimientoCaja.Tipo.RETIRO);
        double gastos = sumaDe(movimientosDeLaCaja, MovimientoCaja.Tipo.GASTO);
        double efectivo = ventasDeLaCaja.stream().filter(v -> v.getMetodoPago() == MetodoPago.EFECTIVO)
                .mapToDouble(VentaService::totalDe).sum();
        Map<String, Double> a = new HashMap<>();
        a.put("fondoInicial", fondo);
        a.put("ventasEfectivo", efectivo);
        a.put("retiros", retiros);
        a.put("gastos", gastos);
        a.put("efectivoEsperado", fondo + efectivo - retiros - gastos);
        return a;
    }

    private static double sumaDe(List<MovimientoCaja> movs, MovimientoCaja.Tipo tipo) {
        return movs.stream().filter(m -> m.getTipo() == tipo)
                .mapToDouble(m -> m.getMonto() != null ? m.getMonto() : 0.0).sum();
    }

    private static List<MovimientoCaja> movimientosEntre(List<MovimientoCaja> todos, LocalDateTime desde, LocalDateTime hasta) {
        return todos.stream()
                .filter(m -> m.getFechaHora() != null && !m.getFechaHora().isBefore(desde)
                        && (hasta == null || m.getFechaHora().isBefore(hasta)))
                .toList();
    }

    private static List<VentaAnulada> anuladasEntre(List<VentaAnulada> todas, LocalDateTime desde, LocalDateTime hasta) {
        return todas.stream()
                .filter(a -> a.getAnuladaEn() != null && !a.getAnuladaEn().isBefore(desde)
                        && (hasta == null || a.getAnuladaEn().isBefore(hasta)))
                .toList();
    }

    private void liberarPedido(Venta venta) {
        Pedido pedido = venta.getPedido();
        if (pedido == null) return;
        pedido.setVenta(null);
        if (pedido.esComandaPorTarjetas()) {
            pedido.setEntregadoEn(null);
            if (pedido.getEstado() == EstadoPedido.ENTREGADO) pedido.setEstado(EstadoPedido.LISTO);
            ComandaMesa.recalcularEstado(pedido, LocalDateTime.now(clock));
        } else if (pedido.getEstado() == EstadoPedido.ENTREGADO) {
            pedido.setEstado(EstadoPedido.LISTO);
            pedido.setEntregadoEn(null);
        }
        pedidoRepository.save(pedido);
    }

    private static double totalDe(Venta v) {
        return v.getTotal() != null ? v.getTotal() : 0.0;
    }

    /** Las ventas de delivery se muestran aparte: esa plata se reparte después. */
    private static boolean esDelivery(Venta v) {
        return v.getPedido() != null && v.getPedido().getTipo() == TipoPedido.DELIVERY;
    }

    private static double envioDe(Venta v) {
        return v.getPedido() != null && v.getPedido().getCostoEnvio() != null ? v.getPedido().getCostoEnvio() : 0.0;
    }

    public VentaResponseDTO toDTO(Venta v) {
        Pedido pedido = v.getPedido();
        String nombre = null;
        if (pedido != null) {
            if (pedido.getNombreCliente() != null && !pedido.getNombreCliente().isBlank()) {
                nombre = pedido.getNombreCliente();
            } else if (pedido.getCliente() != null) {
                nombre = pedido.getCliente().getNombre();
            } else if (pedido.getMesa() != null && !pedido.getMesa().isBlank()) {
                nombre = "Mesa " + pedido.getMesa();
            }
        }
        return VentaResponseDTO.builder()
                .id(v.getId())
                .fecha(v.getFecha())
                .jornada(v.getJornada())
                .hora(v.getHora())
                .total(v.getTotal())
                .metodoPago(v.getMetodoPago())
                .pedidoId(pedido != null ? pedido.getId() : null)
                .nombreCliente(nombre)
                .tipoPedido(pedido != null && pedido.getTipo() != null ? pedido.getTipo().name() : null)
                .mesa(pedido != null ? pedido.getMesa() : null)
                .costoEnvio(envioDe(v))
                .metodoPagoOriginal(v.getMetodoPagoOriginal())
                .corregidoPor(v.getCorregidoPor())
                .corregidoEn(v.getCorregidoEn())
                .mozoNombre(pedido != null && pedido.getMesa() != null
                        ? (pedido.getMozoNombre() != null ? pedido.getMozoNombre() : pedido.getCreadoPorNombre()) : null)
                .cobradoPor(v.getCobradoPor())
                .items(itemsDe(pedido))
                .build();
    }

    /** "2x Flan casero", "1x Muzzarella (Entera)": juntando iguales y sin lo sacado. */
    static List<String> itemsDe(Pedido pedido) {
        if (pedido == null || pedido.getDetalles() == null) return List.of();
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        for (DetallePedido d : pedido.getDetalles()) {
            if (d.estaCancelada() || d.getProducto() == null) continue;
            String nombre = d.getProducto().getNombre()
                    + (d.getVariante() != null && !d.getVariante().isBlank() ? " (" + d.getVariante() + ")" : "");
            cantidades.merge(nombre, d.getCantidad() != null ? d.getCantidad() : 0, Integer::sum);
        }
        List<String> items = new ArrayList<>();
        cantidades.forEach((nombre, cant) -> items.add(cant + "x " + nombre));
        return items;
    }
}
