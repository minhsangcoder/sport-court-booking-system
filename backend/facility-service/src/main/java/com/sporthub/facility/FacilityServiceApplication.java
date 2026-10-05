package com.sporthub.facility;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"com.sporthub.facility","com.sporthub.common"})
@org.springframework.scheduling.annotation.EnableScheduling
public class FacilityServiceApplication {
    public static void main(String[] args){SpringApplication.run(FacilityServiceApplication.class,args);}
}
