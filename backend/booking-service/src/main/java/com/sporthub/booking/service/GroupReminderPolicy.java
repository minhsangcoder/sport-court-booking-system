package com.sporthub.booking.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

/** Configuration controls frequency; Booking remains the payment-window authority. */
@Component
public class GroupReminderPolicy {
    private final Duration minInterval;
    public GroupReminderPolicy(@Value("${booking.group.payment-reminder.min-interval}") String interval) {
        Duration minInterval=Duration.parse(interval);
        if(minInterval.compareTo(Duration.ofSeconds(1))<0)
            throw new IllegalArgumentException("Reminder interval must be at least one second");
        this.minInterval=minInterval;
    }
    public Instant nextAllowedAt(Instant last){return last==null?null:last.plus(minInterval);}
    public boolean windowOpen(String groupState,String bookingState,Instant deadline,Instant endsAt,Instant now){
        return groupState.equals("GROUP_PENDING")&&bookingState.equals("PENDING")&&now.isBefore(deadline)&&now.isBefore(endsAt);
    }
    public String blockedReason(UUID owner,UUID recipient,boolean windowOpen,boolean allocated,
                                BigDecimal due,BigDecimal paid,Instant last,Instant now){
        if(owner.equals(recipient))return "SELF";
        if(!windowOpen)return "WINDOW_CLOSED";
        if(!allocated)return "NO_ALLOCATION";
        if(due.subtract(paid).signum()<=0)return "PAID";
        if(last!=null&&now.isBefore(nextAllowedAt(last)))return "COOLDOWN";
        return null;
    }
}
