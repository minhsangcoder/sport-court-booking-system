package com.sporthub.payment.web;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.payment.repository.PaymentRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
/** Open QR per contribution, so a member and the organizer never both see a live QR. Callers must already hold the booking and member ids. */
@RestController
public class GroupContributionClaimController {
    private final PaymentRepository repo;private final RemoteIdentity identity;private final Clock clock;
    public GroupContributionClaimController(PaymentRepository repo,RemoteIdentity identity,Clock clock){this.repo=repo;this.identity=identity;this.clock=clock;}
    @GetMapping("/api/v1/payments/group-claims")
    public ApiResponse<List<Claim>> claims(@RequestParam UUID bookingId,@RequestParam List<UUID> memberId,HttpServletRequest request){
        if(memberId.size()>50)throw new IllegalArgumentException("At most 50 members");
        UUID caller=identity.current(request).id();
        return ApiResponse.success(repo.jdbc().query(
            "SELECT member_id,payer_id FROM payment_orders WHERE booking_id=? AND purpose='GROUP_CONTRIBUTION' AND status='PENDING' AND expires_at>? AND member_id=ANY(?::uuid[])",
            (r,n)->new Claim(r.getObject("member_id",UUID.class),caller.equals(r.getObject("payer_id",UUID.class))),
            bookingId,Timestamp.from(clock.instant()),"{"+String.join(",",memberId.stream().map(UUID::toString).toList())+"}"));
    }
    public record Claim(UUID memberId,boolean paidByCaller){}
}
