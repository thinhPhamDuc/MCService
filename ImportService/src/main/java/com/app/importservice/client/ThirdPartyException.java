package com.app.importservice.client;

/** Gọi API bên thứ ba thất bại hẳn (đã hết lượt thử lại, hoặc lỗi không nên thử lại). */
public class ThirdPartyException extends RuntimeException {

    public ThirdPartyException(String message, Throwable cause) {
        super(message, cause);
    }
}
