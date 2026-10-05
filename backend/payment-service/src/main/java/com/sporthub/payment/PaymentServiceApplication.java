package com.sporthub.payment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@EnableScheduling @SpringBootApplication(scanBasePackages={"com.sporthub.payment","com.sporthub.common"})
public class PaymentServiceApplication {
 public static void main(String[] args){SpringApplication.run(PaymentServiceApplication.class,args);}
}
