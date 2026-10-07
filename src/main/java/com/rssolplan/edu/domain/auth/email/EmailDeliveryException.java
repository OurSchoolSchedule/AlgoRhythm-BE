package com.rssolplan.edu.domain.auth.email;

public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException() {
        super("이메일 발송 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
