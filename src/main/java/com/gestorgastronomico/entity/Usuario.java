package com.gestorgastronomico.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Entity
@Table(name = "usuarios")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "El nombre es obligatorio")
    @Column(nullable = false)
    private String nombre;

    @Email(message = "El email debe ser válido")
    @NotBlank(message = "El email es obligatorio")
    @Column(nullable = false, unique = true)
    private String email;

    @NotBlank(message = "La contraseña es obligatoria")
    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rol rol;

    // Los usuarios no se borran, se desactivan: así sus pedidos y ventas quedan
    // en el historial. Null (filas anteriores a la columna) cuenta como activo.
    @Builder.Default
    private Boolean activo = true;

    // true = entra con la contraseña de fábrica: el admin tiene que completar sus
    // datos antes de usar el panel, y los demás no pueden entrar hasta que el admin
    // les ponga una contraseña. Null (filas anteriores a la columna) = no.
    private Boolean debeConfigurar;

    public boolean estaActivo() {
        return !Boolean.FALSE.equals(activo);
    }

    public boolean tieneQueConfigurar() {
        return Boolean.TRUE.equals(debeConfigurar);
    }
}
