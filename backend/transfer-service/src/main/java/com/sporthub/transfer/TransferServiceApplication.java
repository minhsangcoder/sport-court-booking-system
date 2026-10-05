package com.sporthub.transfer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@EnableScheduling @SpringBootApplication(scanBasePackages={"com.sporthub.transfer","com.sporthub.common"})
public class TransferServiceApplication {public static void main(String[] args){SpringApplication.run(TransferServiceApplication.class,args);}}
