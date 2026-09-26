package com.app.orderservice.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

    public enum Status { PENDING, PAID, PAYMENT_FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private Long paymentId;

    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Order() {
    }

    public Order(Long userId, String productName, int quantity, BigDecimal totalAmount) {
        this.userId = userId;
        this.productName = productName;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
    }

    public void markPaid(Long paymentId) {
        this.status = Status.PAID;
        this.paymentId = paymentId;
        this.failureReason = null;
    }

    public void markPaymentFailed(Long paymentId, String reason) {
        this.status = Status.PAYMENT_FAILED;
        this.paymentId = paymentId;
        this.failureReason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Status getStatus() {
        return status;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
