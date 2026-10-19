package com.gestorgastronomico.service;

import com.gestorgastronomico.entity.ConfigLocal;
import com.gestorgastronomico.exception.BusinessException;
import com.gestorgastronomico.repository.ConfigLocalRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ConfigLocalServiceTest {

    private final ConfigLocalService service = new ConfigLocalService(mock(ConfigLocalRepository.class));

    @Test
    void configuracionCorrecta_pasaLaValidacion() {
        assertDoesNotThrow(() -> service.validar(valida().build()));
    }

    @Test
    void envioNegativo_seRechaza() {
        assertThrows(BusinessException.class, () -> service.validar(valida().costoDelivery(-500).build()));
    }

    @Test
    void nombreVacioOLargo_seRechaza() {
        assertThrows(BusinessException.class, () -> service.validar(valida().nombre("  ").build()));
        assertThrows(BusinessException.class, () -> service.validar(valida().nombre("x".repeat(61)).build()));
    }

    @Test
    void sinNingunMedioDePagoValido_seRechaza() {
        assertThrows(BusinessException.class, () -> service.validar(valida().pagosAceptados("CHEQUE").build()));
        assertDoesNotThrow(() -> service.validar(valida().pagosAceptados("").build()));
    }

    @Test
    void turnosQueSePisan_seRechazan() {
        assertThrows(BusinessException.class,
                () -> service.validar(valida().nDesde("14:00").nHasta("16:00").build()));
    }

    private static ConfigLocal.ConfigLocalBuilder valida() {
        return ConfigLocal.builder().nombre("La Esquina").costoDelivery(800)
                .pagosAceptados("EFECTIVO,TRANSFERENCIA")
                .mDesde("11:00").mHasta("15:00").nDesde("20:00").nHasta("01:00");
    }

    @Test
    void instagramYFacebook_seGuardanComoUsuario() {
        assertEquals("laesquina", ConfigLocalService.usuarioDeRed("@laesquina", "instagram.com"));
        assertEquals("laesquina", ConfigLocalService.usuarioDeRed("https://www.instagram.com/laesquina/?hl=es", "instagram.com"));
        assertEquals("laesquina", ConfigLocalService.usuarioDeRed("laesquina", "instagram.com"));
        assertEquals("profile.php?id=123", ConfigLocalService.usuarioDeRed("https://facebook.com/profile.php?id=123", "facebook.com"));
    }

    @Test
    void whatsapp_aceptaLasFormasComunes_yRechazaLoQueNoEsUnNumero() {
        assertEquals("3447123456", ConfigLocalService.whatsappNacional("3447 15 123456"));
        assertEquals("3447123456", ConfigLocalService.whatsappNacional("+54 9 3447 123456"));
        assertEquals("3447123456", ConfigLocalService.whatsappNacional("03447-123456"));
        assertNull(ConfigLocalService.whatsappNacional("123"));
        assertNull(ConfigLocalService.whatsappNacional("llamame"));
    }

    @Test
    void envioAbsurdoOTextoLargo_seRechazanConMensajeClaro() {
        BusinessException envio = assertThrows(BusinessException.class, () -> service.validar(valida().costoDelivery(5_000_000).build()));
        assertTrue(envio.getMessage().contains("1.000.000"));
        BusinessException aviso = assertThrows(BusinessException.class, () -> service.validar(valida().avisoTxt("x".repeat(501)).build()));
        assertTrue(aviso.getMessage().contains("aviso"));
    }
}
