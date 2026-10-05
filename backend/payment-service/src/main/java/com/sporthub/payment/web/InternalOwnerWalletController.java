package com.sporthub.payment.web;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.ServiceCalls;
import com.sporthub.common.exception.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.math.BigDecimal;
@RestController
public class InternalOwnerWalletController {
 private final String secret;private final JdbcTemplate jdbc;private final ObjectMapper json;
 public InternalOwnerWalletController(@Value("${SERVICE_CALL_SECRET:}") String secret,JdbcTemplate jdbc,ObjectMapper json){this.secret=secret;this.jdbc=jdbc;this.json=json;}
 @PostMapping("/api/v1/internal/owner-wallets/prepare") @Transactional public ApiResponse<Map<String,Object>> prepare(@RequestBody String body,HttpServletRequest r)throws Exception{
  ServiceCalls.verify(secret,r.getMethod(),r.getRequestURI(),r.getHeader("X-Service-Time"),body,r.getHeader("X-Service-Signature"));var data=json.readTree(body);UUID application=UUID.fromString(data.path("applicationId").asText()),owner=UUID.fromString(data.path("userId").asText());BigDecimal commission=new BigDecimal(data.path("commissionPercent").asText());if(commission.signum()<0||commission.compareTo(new BigDecimal("100"))>0||commission.scale()>2)throw new IllegalArgumentException("Invalid explicit commission percentage");jdbc.update("INSERT INTO owner_wallets(owner_id,application_id,beneficiary_reference,commission_percent) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",owner,application,application,commission);var wallet=jdbc.queryForMap("SELECT * FROM owner_wallets WHERE owner_id=? FOR UPDATE",owner);if(!application.equals(wallet.get("application_id"))||commission.compareTo((BigDecimal)wallet.get("commission_percent"))!=0)throw new ConflictException("Owner wallet was initialized by a different approval");return ApiResponse.success(Map.of("ownerId",owner,"applicationId",application,"initialized",true));
 }
}
