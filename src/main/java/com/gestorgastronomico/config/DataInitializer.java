package com.gestorgastronomico.config;

import com.gestorgastronomico.entity.*;
import com.gestorgastronomico.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {

    private final PasswordEncoder passwordEncoder;

    @Bean
    public CommandLineRunner initData(
            UsuarioRepository usuarioRepo,
            ProductoRepository productoRepo,
            JdbcTemplate jdbc) {

        return args -> {
            // Hibernate crea usuarios.rol como ENUM de MySQL; como VARCHAR acepta roles nuevos sin migraciones.
            try {
                jdbc.execute("ALTER TABLE usuarios MODIFY COLUMN rol VARCHAR(20) NOT NULL");
                log.info("Columna usuarios.rol normalizada a VARCHAR(20)");
            } catch (Exception e) {
                log.warn("No se pudo normalizar usuarios.rol (normal en el primer arranque): {}", e.getMessage());
            }

            // Datos iniciales solo si la base está vacía.
            if (usuarioRepo.count() == 0) {
                log.info("=== Cargando datos iniciales del sistema ===");

                // Solo el admin, con la contraseña de fábrica: al entrar por primera vez
                // carga su nombre, su email y una contraseña propia, y después crea al
                // resto del equipo en Usuarios.
                usuarioRepo.save(Usuario.builder()
                        .nombre("Administrador")
                        .email("admin@miapp.com")
                        .password(passwordEncoder.encode("admin123"))
                        .rol(Rol.ADMIN)
                        .debeConfigurar(true).build());

                log.info("  ✓ Admin inicial creado (completa sus datos al primer ingreso)");

                // Un solo producto de ejemplo, para borrar antes de cargar el menú real.
                productoRepo.save(p("Plato de ejemplo (borrame)",
                        "Este producto es solo de muestra — borralo desde Gestor de menú antes de cargar el menú real",
                        1000.0, "Ejemplo"));

                log.info("  ✓ {} producto de ejemplo cargado", productoRepo.count());
                log.info("=== Datos iniciales cargados correctamente ===");
            }
        };
    }

    private Producto p(String nombre, String desc, Double precio, String categoria) {
        return Producto.builder()
                .nombre(nombre)
                .descripcion(desc.isBlank() ? null : desc)
                .precio(precio)
                .activo(true)
                .categoria(categoria)
                .build();
    }
}
