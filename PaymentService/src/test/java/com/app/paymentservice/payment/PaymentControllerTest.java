package com.app.paymentservice.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentControllerTest {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    private ChaosSettings chaos;
    private PaymentController controller;

    @BeforeEach
    void setUp() {
        PaymentRepository repository = mock(PaymentRepository.class);
        when(repository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        chaos = new ChaosSettings(0, 0.0);
        controller = new PaymentController(repository, chaos, MAX_AMOUNT);
    }

    @Test
    void amountWithinLimitSucceeds() throws Exception {
        PaymentController.PaymentResponse response =
                controller.pay(new PaymentController.PaymentRequest(1L, 1L, new BigDecimal("2000000")));

        assertThat(response.status()).isEqualTo("SUCCESS");
    }

    @Test
    void amountEqualToLimitSucceeds() throws Exception {
        PaymentController.PaymentResponse response =
                controller.pay(new PaymentController.PaymentRequest(1L, 1L, MAX_AMOUNT));

        assertThat(response.status()).isEqualTo("SUCCESS");
    }

    @Test
    void amountOverLimitIsDeclined() throws Exception {
        PaymentController.PaymentResponse response =
                controller.pay(new PaymentController.PaymentRequest(1L, 1L, new BigDecimal("99000000")));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).contains("exceeds limit");
    }

    @Test
    void chaosErrorRateOneAlwaysFails() {
        chaos.setErrorRate(1.0);

        assertThatThrownBy(() -> controller.pay(new PaymentController.PaymentRequest(1L, 1L, BigDecimal.TEN)))
                .isInstanceOf(IllegalStateException.class);
    }
}
