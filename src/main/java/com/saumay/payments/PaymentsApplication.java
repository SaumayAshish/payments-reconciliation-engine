package com.saumay.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentsApplication {

    public static void main(String[] args) {
        if (AuditVerifyCommand.requested(args)) {
            System.exit(AuditVerifyCommand.run(args));
        }
        SpringApplication.run(PaymentsApplication.class, args);
    }
}