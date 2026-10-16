package com.mindtechpy.nexus.domain;

import com.mindtechpy.nexus.domain.operation.ErrorMessages;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMessagesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "amount-mismatch|El monto cobrado no coincide con el total del pedido en el POS",
            "insufficient-stock|El POS no tiene stock suficiente para el pedido",
            "invalid-ruc|El POS rechazó el RUC del cliente",
            "cash-session-closed|El POS no tiene la caja abierta",
            "TRANSITION_NOT_ALLOWED|GiftHub no permite ese cambio de estado del pedido",
            "VALIDATION_ERROR|SIFEN rechazó los datos del documento"})
    void should_give_the_fixed_spanish_message_when_the_code_is_known(String code, String message) {
        assertThat(ErrorMessages.describe(code)).isEqualTo(message);
    }

    @Test
    void should_name_the_code_in_a_generic_message_when_the_code_is_unknown() {
        assertThat(ErrorMessages.describe("pos-http-418")).isEqualTo("Error del sistema remoto (pos-http-418)");
    }
}
