package com.app.paymentservice.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Bắt mọi exception chưa được xử lý và ghi log ngay trong request (lúc MDC vẫn còn requestId).
 * Nếu để exception lọt ra ngoài, Tomcat mới log stack trace sau khi RequestIdFilter đã xoá requestId,
 * khi đó trên Kibana sẽ không lần ra được lỗi thuộc request nào.
 * Các lỗi chuẩn của Spring (400, 404, ResponseStatusException...) vẫn do lớp cha xử lý như bình thường.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled error: {}", e.getMessage(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }
}
