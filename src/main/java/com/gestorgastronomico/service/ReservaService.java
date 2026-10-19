package com.gestorgastronomico.service;

import com.gestorgastronomico.dto.ReservaRequestDTO;
import com.gestorgastronomico.dto.ReservaResponseDTO;
import com.gestorgastronomico.entity.Cliente;
import com.gestorgastronomico.entity.ConfigLocal;
import com.gestorgastronomico.entity.Reserva;
import com.gestorgastronomico.entity.Reserva.EstadoReserva;
import com.gestorgastronomico.exception.BusinessException;
import com.gestorgastronomico.exception.ResourceNotFoundException;
import com.gestorgastronomico.repository.ClienteRepository;
import com.gestorgastronomico.repository.ReservaRepository;
import com.gestorgastronomico.websocket.RealtimeNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservaService {

    /** Hasta cuántos días adelante se puede reservar. */
    static final int DIAS_MAXIMOS_DE_ANTICIPACION = 90;
    /** El día de trabajo cambia a esta hora: a la 01:00 sigue siendo la noche anterior. */
    private static final int HORA_CAMBIO_DE_DIA = 6;

    private final ReservaRepository reservaRepository;
    private final ClienteRepository clienteRepository;
    private final ClienteService clienteService;
    private final ConfigLocalService configLocalService;
    private final RealtimeNotifier realtimeNotifier;
    private final EnvioDuplicado envioDuplicado;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ReservaResponseDTO> listarTodas() {
        return reservaRepository.findAll().stream().map(this::toDTO).toList();
    }

    /**
     * Reservas de hoy, incluidas las canceladas, ordenadas por hora. Suma las de
     * la madrugada siguiente que caen dentro del turno de esta noche.
     */
    @Transactional(readOnly = true)
    public List<ReservaResponseDTO> listarDeHoy() {
        return delDiaDeTrabajo(diaDeTrabajo(LocalDateTime.now(clock))).stream().map(this::toDTO).toList();
    }

    /** Hasta las 6 de la mañana sigue siendo el día anterior (el turno de la noche). */
    static LocalDate diaDeTrabajo(LocalDateTime momento) {
        return momento.getHour() < HORA_CAMBIO_DE_DIA ? momento.toLocalDate().minusDays(1) : momento.toLocalDate();
    }

    /**
     * Reservas de un día de trabajo: las de ese día, salvo las de la madrugada que
     * pertenecen a la noche anterior, y al final las de la madrugada siguiente que
     * caen dentro del turno de esa noche.
     */
    private List<Reserva> delDiaDeTrabajo(LocalDate dia) {
        ConfigLocal cfg = configLocalService.obtener();
        List<Reserva> lista = new ArrayList<>();
        reservaRepository.findByFechaOrderByHoraAsc(dia).stream()
                .filter(r -> !esDeLaNocheAnterior(cfg, r))
                .forEach(lista::add);
        reservaRepository.findByFechaOrderByHoraAsc(dia.plusDays(1)).stream()
                .filter(r -> esDeLaNocheAnterior(cfg, r))
                .forEach(lista::add);
        return lista;
    }

    /** Una reserva de madrugada que cae en el turno que empezó el día anterior. */
    private static boolean esDeLaNocheAnterior(ConfigLocal cfg, Reserva r) {
        if (r.getFecha() == null || r.getHora() == null || r.getHora().getHour() >= HORA_CAMBIO_DE_DIA) return false;
        return HorarioLocal.turnoDe(cfg, r.getFecha().atTime(r.getHora()))
                .map(t -> t.inicio().isBefore(r.getFecha()))
                .orElse(false);
    }

    /** Busca por nombre o teléfono entre las reservas de los últimos 30 días y las que vienen. */
    @Transactional(readOnly = true)
    public List<ReservaResponseDTO> buscar(String texto) {
        String q = texto == null ? "" : texto.trim().toLowerCase();
        if (q.length() < 2) throw new BusinessException("Escribí al menos 2 letras o números para buscar.");
        String digitos = q.replaceAll("\\D", "");
        LocalDate hoy = LocalDate.now(clock);
        return reservaRepository.findAll().stream()
                .filter(r -> r.getFecha() != null && !r.getFecha().isBefore(hoy.minusDays(30)))
                .filter(r -> (r.getNombreCliente() != null && r.getNombreCliente().toLowerCase().contains(q))
                        || (digitos.length() >= 3 && r.getTelefono() != null
                            && r.getTelefono().replaceAll("\\D", "").contains(digitos)))
                .sorted(java.util.Comparator.comparing(Reserva::getFecha).thenComparing(Reserva::getHora))
                .limit(50)
                .map(this::toDTO)
                .toList();
    }

    /** Reservas confirmadas desde hoy hasta dentro de N días (avisos y recordatorios). */
    @Transactional(readOnly = true)
    public List<ReservaResponseDTO> listarProximas(int dias) {
        LocalDate desde = diaDeTrabajo(LocalDateTime.now(clock));
        // Hasta la madrugada siguiente al último día (el turno de la noche).
        LocalDate hasta = desde.plusDays(Math.max(dias, 0) + 1L);
        return reservaRepository
                .findByFechaBetweenAndEstadoOrderByFechaAscHoraAsc(desde, hasta, EstadoReserva.CONFIRMADA)
                .stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public List<ReservaResponseDTO> listarPorFecha(LocalDate fecha) {
        return delDiaDeTrabajo(fecha).stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public ReservaResponseDTO obtenerPorId(Long id) {
        return toDTO(buscar(id));
    }

    public ReservaResponseDTO crear(ReservaRequestDTO dto) {
        validar(dto, null);
        avisarSiRepetida(dto, null);

        String firma = String.join("|", "RES", texto(dto.getTelefono()), String.valueOf(dto.getFecha()),
                String.valueOf(dto.getHora()), texto(dto.getNombreCliente()));
        if (envioDuplicado.esRepetido(firma)) {
            throw new BusinessException("Ya recibimos esta reserva hace unos segundos. Esperá un momento antes de reenviarla.");
        }

        Reserva reserva = Reserva.builder()
                .nombreCliente(dto.getNombreCliente().trim())
                .fecha(dto.getFecha())
                .hora(dto.getHora())
                .cantidadPersonas(dto.getCantidadPersonas())
                .telefono(dto.getTelefono())
                .aclaraciones(dto.getAclaraciones())
                .estado(EstadoReserva.CONFIRMADA)
                .creadaPor(usuarioLogueado())
                .build();

        if (dto.getClienteId() != null) {
            Cliente cliente = clienteRepository.findById(dto.getClienteId())
                    .orElseThrow(() -> new ResourceNotFoundException("Cliente", dto.getClienteId()));
            reserva.setCliente(cliente);
        }
        return guardarYAvisar(reserva);
    }

    public ReservaResponseDTO actualizar(Long id, ReservaRequestDTO dto) {
        Reserva reserva = buscar(id);
        if (reserva.getEstado() != EstadoReserva.CONFIRMADA) {
            throw new BusinessException("Solo se pueden editar reservas confirmadas.");
        }
        // Nombre, teléfono y aclaraciones se corrigen siempre; fecha, hora y
        // personas se revisan solo si cambian.
        boolean cambiaElTurno = !Objects.equals(dto.getFecha(), reserva.getFecha())
                || !Objects.equals(dto.getHora(), reserva.getHora())
                || !Objects.equals(dto.getCantidadPersonas(), reserva.getCantidadPersonas());
        if (cambiaElTurno) {
            validar(dto, id);
            avisarSiRepetida(dto, id);
        }
        reserva.setNombreCliente(dto.getNombreCliente().trim());
        reserva.setFecha(dto.getFecha());
        reserva.setHora(dto.getHora());
        reserva.setCantidadPersonas(dto.getCantidadPersonas());
        reserva.setTelefono(dto.getTelefono());
        reserva.setAclaraciones(dto.getAclaraciones());
        return guardarYAvisar(reserva);
    }

    public ReservaResponseDTO cancelar(Long id) {
        Reserva reserva = buscar(id);
        if (reserva.getEstado() == EstadoReserva.CANCELADA) {
            throw new BusinessException("La reserva ya está cancelada");
        }
        if (reserva.getEstado() == EstadoReserva.COMPLETADA) {
            throw new BusinessException("No se puede cancelar una reserva de un cliente que ya llegó");
        }
        reserva.setEstado(EstadoReserva.CANCELADA);
        return guardarYAvisar(reserva);
    }

    /** El cliente llegó. */
    public ReservaResponseDTO completar(Long id) {
        Reserva reserva = buscar(id);
        if (reserva.getEstado() != EstadoReserva.CONFIRMADA) {
            throw new BusinessException("Solo se puede marcar la llegada de una reserva confirmada");
        }
        reserva.setEstado(EstadoReserva.COMPLETADA);
        return guardarYAvisar(reserva);
    }

    /** El cliente no vino: la reserva se cancela y queda marcada para saber por qué. */
    public ReservaResponseDTO marcarNoAsistio(Long id) {
        Reserva reserva = buscar(id);
        if (reserva.getEstado() != EstadoReserva.CONFIRMADA) {
            throw new BusinessException("Solo se puede marcar \"no vino\" en una reserva confirmada");
        }
        reserva.setEstado(EstadoReserva.CANCELADA);
        reserva.setNoAsistio(true);
        return guardarYAvisar(reserva);
    }

    private void validar(ReservaRequestDTO dto, Long idEditando) {
        LocalDateTime momento = dto.getFecha().atTime(dto.getHora());
        LocalDateTime ahora = LocalDateTime.now(clock);
        if (momento.isBefore(ahora)) {
            if (dto.getFecha().equals(ahora.toLocalDate()) && dto.getHora().getHour() < 6 && ahora.getHour() >= 6) {
                throw new BusinessException("Para después de medianoche elegí la fecha del día siguiente.");
            }
            throw new BusinessException("No se pueden hacer reservas en una fecha u hora que ya pasó");
        }
        if (dto.getFecha().isAfter(ahora.toLocalDate().plusDays(DIAS_MAXIMOS_DE_ANTICIPACION))) {
            throw new BusinessException("Se puede reservar hasta " + DIAS_MAXIMOS_DE_ANTICIPACION + " días antes.");
        }

        ConfigLocal cfg = configLocalService.obtener();
        int maximo = cfg.limitePersonasReserva();
        if (dto.getCantidadPersonas() > maximo) {
            throw new BusinessException("El máximo por reserva es de " + maximo + " personas. Para grupos más grandes, comunicate con el local.");
        }

        Optional<HorarioLocal.Turno> turno = HorarioLocal.turnoDe(cfg, momento);
        if (turno.isEmpty()) {
            throw new BusinessException("El local está cerrado a esa hora. Elegí un día y horario de atención.");
        }

        if (cfg.tieneLimitePorTurno()) {
            int ocupadas = personasReservadasEnTurno(cfg, turno.get(), idEditando);
            int libres = cfg.getMaxPersonasTurno() - ocupadas;
            if (dto.getCantidadPersonas() > libres) {
                throw new BusinessException(libres <= 0
                        ? "No quedan lugares para ese turno. Probá con otro horario."
                        : "Para ese turno quedan lugares para " + libres + " personas.");
            }
        }
    }

    /**
     * Si ya hay una reserva confirmada con ese teléfono para ese día, avisa. En el
     * panel se puede cargar igual (igualSiRepetida); desde la web, no.
     */
    private void avisarSiRepetida(ReservaRequestDTO dto, Long idEditando) {
        String tel = dto.getTelefono() == null ? "" : dto.getTelefono().replaceAll("\\D", "");
        if (tel.length() < 6) return;
        Optional<Reserva> otra = reservaRepository.findByFechaOrderByHoraAsc(dto.getFecha()).stream()
                .filter(r -> r.getEstado() == EstadoReserva.CONFIRMADA && !Objects.equals(r.getId(), idEditando))
                .filter(r -> r.getTelefono() != null && r.getTelefono().replaceAll("\\D", "").equals(tel))
                .findFirst();
        if (otra.isEmpty()) return;
        Reserva r = otra.get();
        String hora = r.getHora() != null ? r.getHora().toString().substring(0, 5) : "";
        if (!estaLogueado()) {
            throw new BusinessException("Ya hay una reserva con ese teléfono para ese día. Si querés cambiarla, comunicate con el local.");
        }
        if (!Boolean.TRUE.equals(dto.getIgualSiRepetida())) {
            throw new BusinessException("REPETIDA: Ya hay una reserva de " + r.getNombreCliente() + " para ese día a las "
                    + hora + " (" + r.getCantidadPersonas() + " personas).");
        }
    }

    private static boolean estaLogueado() {
        return usuarioLogueado() != null;
    }

    /** Email del usuario del panel, o null si la pide la página pública. */
    private static String usuarioLogueado() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return null;
        return auth.getName();
    }

    /** La reserva es de un día de trabajo que ya terminó. */
    private boolean esPasada(Reserva r) {
        if (r.getFecha() == null || r.getHora() == null) return false;
        LocalDate hoy = diaDeTrabajo(LocalDateTime.now(clock));
        return diaDeTrabajo(r.getFecha().atTime(r.getHora())).isBefore(hoy);
    }

    private int personasReservadasEnTurno(ConfigLocal cfg, HorarioLocal.Turno turno, Long excluirId) {
        LocalDate desde = turno.inicio();
        return reservaRepository
                .findByFechaBetweenAndEstadoOrderByFechaAscHoraAsc(desde, desde.plusDays(1), EstadoReserva.CONFIRMADA)
                .stream()
                .filter(r -> !Objects.equals(r.getId(), excluirId))
                .filter(r -> HorarioLocal.turnoDe(cfg, r.getFecha().atTime(r.getHora()))
                        .map(turno::equals).orElse(false))
                .mapToInt(r -> r.getCantidadPersonas() != null ? r.getCantidadPersonas() : 0)
                .sum();
    }

    private Reserva buscar(Long id) {
        return reservaRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Reserva", id));
    }

    private ReservaResponseDTO guardarYAvisar(Reserva reserva) {
        ReservaResponseDTO resultado = toDTO(reservaRepository.save(reserva));
        realtimeNotifier.avisarReservas();
        return resultado;
    }

    private static String texto(String valor) {
        return valor == null ? "" : valor.trim();
    }

    public ReservaResponseDTO toDTO(Reserva r) {
        return ReservaResponseDTO.builder()
                .id(r.getId())
                .nombreCliente(r.getNombreCliente())
                .fecha(r.getFecha())
                .hora(r.getHora())
                .cantidadPersonas(r.getCantidadPersonas())
                .telefono(r.getTelefono())
                .aclaraciones(r.getAclaraciones())
                .estado(r.getEstado())
                .noAsistio(Boolean.TRUE.equals(r.getNoAsistio()))
                .cliente(r.getCliente() != null ? clienteService.toDTO(r.getCliente()) : null)
                .cancelable(r.getEstado() == EstadoReserva.CONFIRMADA && !esPasada(r))
                .creadaPor(r.getCreadaPor())
                .pasada(esPasada(r))
                .build();
    }
}
