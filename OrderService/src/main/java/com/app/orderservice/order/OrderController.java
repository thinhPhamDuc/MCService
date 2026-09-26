package com.app.orderservice.order;

import com.app.orderservice.client.PaymentClient;
import com.app.orderservice.client.UserClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderRepository orderRepository;
    private final UserClient userClient;
    private final PaymentClient paymentClient;

    public OrderController(OrderRepository orderRepository, UserClient userClient, PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.userClient = userClient;
        this.paymentClient = paymentClient;
    }

    public record CreateOrderRequest(String productName, int quantity, BigDecimal price) {
    }

    /**
     * Luồng mua hàng:
     * 1. Gọi UserService xác thực token
     * 2. Lưu order trạng thái PENDING
     * 3. Gọi PaymentService thanh toán
     * 4. Cập nhật order thành PAID hoặc PAYMENT_FAILED
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Order create(@RequestHeader(value = "Authorization", required = false) String authorization,
                        @RequestBody CreateOrderRequest request) {
        UserClient.UserInfo user = userClient.validate(authorization);

        BigDecimal total = request.price().multiply(BigDecimal.valueOf(request.quantity()));
        Order order = orderRepository.save(new Order(user.userId(), request.productName(), request.quantity(), total));
        log.info("Order created orderId={} userId={} total={}", order.getId(), user.userId(), total);

        try {
            PaymentClient.PaymentResponse payment = paymentClient.pay(order.getId(), user.userId(), total);
            if ("SUCCESS".equals(payment.status())) {
                order.markPaid(payment.paymentId());
                log.info("Order paid orderId={} paymentId={}", order.getId(), payment.paymentId());
            } else {
                order.markPaymentFailed(payment.paymentId(), payment.message());
                log.warn("Order payment declined orderId={} reason={}", order.getId(), payment.message());
            }
        } catch (RestClientException e) {
            // PaymentService chết / timeout / trả 5xx: order vẫn được lưu nhưng đánh dấu thất bại
            order.markPaymentFailed(null, "PaymentService error: " + e.getMessage());
            log.error("Payment call failed orderId={}: {}", order.getId(), e.getMessage(), e);
        }
        return orderRepository.save(order);
    }

    @GetMapping
    public List<Order> myOrders(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UserClient.UserInfo user = userClient.validate(authorization);
        return orderRepository.findByUserIdOrderByIdDesc(user.userId());
    }

    @GetMapping("/{id}")
    public Order get(@RequestHeader(value = "Authorization", required = false) String authorization,
                     @PathVariable Long id) {
        UserClient.UserInfo user = userClient.validate(authorization);
        return orderRepository.findById(id)
                .filter(o -> o.getUserId().equals(user.userId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }
}
