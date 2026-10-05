package com.sporthub.booking.service;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.Booking;
import com.sporthub.common.event.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** Records refund review requests; never selects or executes a disputed refund policy. */
@Component
public class GroupExpiration {
    private final BookingRepository repo;
    private final ObjectProvider<ReliableOutbox> outbox;
    public GroupExpiration(BookingRepository repo,ObjectProvider<ReliableOutbox> outbox){this.repo=repo;this.outbox=outbox;}
    public void expire(Booking booking) {
        if(repo.jdbc().update("UPDATE booking_groups SET state='GROUP_EXPIRED' WHERE id=? AND state='GROUP_PENDING'",booking.id())==0)return;
        for(var row:repo.jdbc().queryForList("SELECT * FROM group_contributions WHERE group_id=?",booking.id()))
            requestReview(booking.id(),(UUID)row.get("payment_id"),(UUID)row.get("payer_id"),"GROUP_TIMEOUT");
        repo.history(booking.id(),null,"GROUP_EXPIRED",Map.of("refundPolicy","BLOCKED_RULE"));
    }
    public void requestReview(UUID booking,UUID payment,UUID payer,String reason) {
        var publisher=outbox.getIfAvailable();
        if(publisher!=null)publisher.record(RabbitMQConfig.EXCHANGE_BOOKING,
            DomainEvent.create("booking.group.refund.requested",1,"booking-service",booking,
                Map.of("bookingId",booking,"paymentId",payment,"payerId",payer,"reason",reason,"policyState","BLOCKED_RULE")));
    }
}
