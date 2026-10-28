package com.gestorgastronomico.config;

import com.gestorgastronomico.entity.Usuario;
import com.gestorgastronomico.repository.UsuarioRepository;
import com.gestorgastronomico.service.UsuarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Al arrancar, marca a los usuarios que siguen con una contraseña de fábrica
 * (admin123, cajero123, ...). El admin marcado tiene que completar sus datos al
 * entrar; los demás no pueden entrar hasta que el admin les ponga una contraseña.
 */
@Component
public class ClavesDeFabrica {

    private static final Logger log = LoggerFactory.getLogger(ClavesDeFabrica.class);

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    public ClavesDeFabrica(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void marcar() {
        List<Usuario> marcados = new ArrayList<>();
        for (Usuario u : usuarioRepository.findAll()) {
            if (u.tieneQueConfigurar()) continue;
            boolean deFabrica = UsuarioService.CLAVES_DE_FABRICA.stream()
                    .anyMatch(clave -> passwordEncoder.matches(clave, u.getPassword()));
            if (deFabrica) {
                u.setDebeConfigurar(true);
                marcados.add(u);
            }
        }
        if (!marcados.isEmpty()) {
            usuarioRepository.saveAll(marcados);
            log.warn("ClavesDeFabrica: {} usuario(s) con contraseña de fábrica quedaron bloqueados hasta cambiarla: {}",
                    marcados.size(), marcados.stream().map(Usuario::getEmail).toList());
        }
    }
}
