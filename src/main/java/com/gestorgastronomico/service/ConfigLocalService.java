package com.gestorgastronomico.service;

import com.gestorgastronomico.entity.ConfigLocal;
import com.gestorgastronomico.entity.MetodoPago;
import com.gestorgastronomico.exception.BusinessException;
import com.gestorgastronomico.repository.ConfigLocalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConfigLocalService {

    public static final int MAX_LARGO_NOMBRE = 60;
    private static final Long ID = 1L;
    private static final int MAX_PERSONAS_RESERVA = 200;

    private final ConfigLocalRepository repo;

    public ConfigLocal obtener() {
        return repo.findById(ID).orElseGet(() -> repo.save(porDefecto()));
    }

    /**
     * Guarda la configuración que manda el panel. El estado de la caja no viaja
     * en el JSON, así que se conserva el que está en la base.
     */
    public ConfigLocal guardarDesdePanel(ConfigLocal cfg) {
        validar(cfg);
        ConfigLocal actual = obtener();
        cfg.setCierreCaja(actual.getCierreCaja());
        // Se editan desde Gestor de menú; un panel viejo no los manda.
        if (cfg.getCategoriasSinCocina() == null) cfg.setCategoriasSinCocina(actual.getCategoriasSinCocina());
        if (cfg.getImprimirComandaCocina() == null) cfg.setImprimirComandaCocina(actual.getImprimirComandaCocina());
        // Un panel anterior no conoce el fondo del login: si no lo manda, queda el que estaba.
        if (cfg.getFondoLoginUrl() == null) cfg.setFondoLoginUrl(actual.getFondoLoginUrl());
        cfg.setCajaAbierta(actual.getCajaAbierta());
        cfg.setLoginEmails("[]");
        cfg.setHorarioLibre("");
        cfg.setNombre(cfg.getNombre().trim());
        return guardar(cfg);
    }

    /** Guarda qué categorías no pasan por cocina (bebidas). Lista vacía = todas van a cocina. */
    public ConfigLocal guardarCategoriasSinCocina(List<String> categorias) {
        String texto = categorias == null ? "" : categorias.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        if (texto.length() > 1000) throw new BusinessException("Son demasiadas categorías.");
        ConfigLocal cfg = obtener();
        cfg.setCategoriasSinCocina(texto);
        return guardar(cfg);
    }

    /** Abre, cierra o vuelve a modo automático sin tocar el resto de la configuración. */
    public ConfigLocal cambiarEstadoManual(String modo) {
        if (!Set.of("auto", "open", "close").contains(modo)) {
            throw new BusinessException("Estado inválido: usá auto, open o close.");
        }
        ConfigLocal cfg = obtener();
        cfg.setEstadoManual(modo);
        return guardar(cfg);
    }

    public ConfigLocal guardar(ConfigLocal cfg) {
        cfg.setId(ID);
        return repo.save(cfg);
    }

    /** Copia de la configuración sin imágenes, para los refrescos periódicos de la web. */
    public ConfigLocal obtenerSinImagenes() {
        return obtener().toBuilder().logoUrl(null).heroUrl(null).fondoLoginUrl(null).build();
    }

    void validar(ConfigLocal cfg) {
        String nombre = cfg.getNombre() == null ? "" : cfg.getNombre().trim();
        if (nombre.isEmpty()) {
            throw new BusinessException("El nombre del local es obligatorio.");
        }
        if (nombre.length() > MAX_LARGO_NOMBRE) {
            throw new BusinessException("El nombre del local puede tener hasta " + MAX_LARGO_NOMBRE + " caracteres.");
        }
        largoMaximo(cfg.getDir(), 500, "La dirección");
        largoMaximo(cfg.getSlogan(), 500, "El slogan");
        largoMaximo(cfg.getAvisoTxt(), 500, "El aviso");
        cfg.setIg(usuarioDeRed(cfg.getIg(), "instagram.com"));
        cfg.setFb(usuarioDeRed(cfg.getFb(), "facebook.com"));
        largoMaximo(cfg.getIg(), 100, "El usuario de Instagram");
        largoMaximo(cfg.getFb(), 100, "El usuario de Facebook");
        String wa = cfg.getWa() == null ? "" : cfg.getWa().trim();
        if (!wa.isEmpty() && whatsappNacional(wa) == null) {
            throw new BusinessException("El WhatsApp tiene que tener la característica y el número (10 números, por ejemplo 3447 15 123456).");
        }
        if (negativo(cfg.getCostoDelivery())) {
            throw new BusinessException("El costo de envío no puede ser negativo.");
        }
        if (cfg.getCostoDelivery() != null && cfg.getCostoDelivery() > MONTO_MAXIMO) {
            throw new BusinessException("El costo de envío no puede superar $1.000.000.");
        }
        if (cfg.getMinimo() != null && cfg.getMinimo() > MONTO_MAXIMO) {
            throw new BusinessException("El pedido mínimo no puede superar $1.000.000.");
        }
        if (negativo(cfg.getMinimo())) {
            throw new BusinessException("El pedido mínimo no puede ser negativo.");
        }
        if (negativo(cfg.getRadioDelivery())) {
            throw new BusinessException("El radio de delivery no puede ser negativo.");
        }
        String pagos = cfg.getPagosAceptados();
        if (pagos != null && !pagos.isBlank() && metodosValidos(pagos).isEmpty()) {
            throw new BusinessException("Elegí al menos un medio de pago.");
        }
        Integer porReserva = cfg.getMaxPersonasReserva();
        if (porReserva != null && (porReserva < 1 || porReserva > MAX_PERSONAS_RESERVA)) {
            throw new BusinessException("El máximo de personas por reserva tiene que estar entre 1 y " + MAX_PERSONAS_RESERVA + ".");
        }
        if (negativo(cfg.getMaxPersonasTurno())) {
            throw new BusinessException("El máximo de personas por turno no puede ser negativo.");
        }
        String errorTurnos = HorarioLocal.validarTurnos(cfg);
        if (errorTurnos != null) {
            throw new BusinessException(errorTurnos);
        }
    }

    private static final int MONTO_MAXIMO = 1_000_000;

    private static void largoMaximo(String texto, int maximo, String campo) {
        if (texto != null && texto.trim().length() > maximo) {
            throw new BusinessException(campo + " puede tener hasta " + maximo + " letras.");
        }
    }

    /**
     * Instagram o Facebook: acepta el usuario, @usuario o el link, y guarda siempre
     * el usuario solo ("laesquina").
     */
    static String usuarioDeRed(String valor, String dominio) {
        if (valor == null) return null;
        String v = valor.trim();
        if (v.isEmpty()) return "";
        v = v.replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^(www\\.|m\\.)", "");
        if (v.toLowerCase().startsWith(dominio)) v = v.substring(dominio.length());
        v = v.replaceFirst("^/+", "");
        int corte = v.indexOf('?');
        if (corte >= 0 && !v.startsWith("profile.php")) v = v.substring(0, corte);
        v = v.replaceFirst("/+$", "").replaceFirst("^@", "").trim();
        if (v.matches(".*[\\s<>\"'`].*")) {
            throw new BusinessException("El usuario de " + (dominio.startsWith("insta") ? "Instagram" : "Facebook") + " no puede tener espacios ni < > \" ' `");
        }
        return v;
    }

    /**
     * Número de WhatsApp argentino en 10 números (característica + número, sin 0
     * ni 15). Null si no tiene ese formato.
     */
    static String whatsappNacional(String numero) {
        if (!numero.matches("[\\d\\s\\-+().]+")) return null;
        String d = numero.replaceAll("\\D", "");
        if (d.startsWith("00")) d = d.substring(2);
        if (d.startsWith("549")) d = d.substring(3);
        else if (d.startsWith("54")) d = d.substring(2);
        if (d.startsWith("0")) d = d.substring(1);
        for (int area : new int[]{4, 3, 2}) {
            if (d.length() == 12 && d.startsWith("15", area)) { d = d.substring(0, area) + d.substring(area + 2); break; }
        }
        return d.length() == 10 ? d : null;
    }

    private static boolean negativo(Integer valor) {
        return valor != null && valor < 0;
    }

    private static Set<String> metodosValidos(String texto) {
        Set<String> nombres = Arrays.stream(MetodoPago.values()).map(Enum::name).collect(Collectors.toSet());
        return Arrays.stream(texto.split(","))
                .map(s -> s.trim().toUpperCase())
                .filter(nombres::contains)
                .collect(Collectors.toSet());
    }

    private ConfigLocal porDefecto() {
        return ConfigLocal.builder()
                .id(ID)
                .nombre("Mi Negocio")
                .dir("")
                .tel("")
                .slogan("Pedí online, elegí retiro o delivery, o reservá tu mesa.")
                .dias("")
                .mDesde("")
                .mHasta("")
                .nDesde("")
                .nHasta("")
                .horarioLibre("")
                .ig("").wa("").fb("")
                .avisoTxt("")
                .heroUrl("")
                .herotextcolor("")
                .maxPersonasReserva(ConfigLocal.MAX_PERSONAS_RESERVA_POR_DEFECTO)
                .build();
    }
}
