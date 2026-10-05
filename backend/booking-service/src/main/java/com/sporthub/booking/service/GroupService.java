package com.sporthub.booking.service;

import static com.sporthub.booking.web.GroupDtos.*;
import com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.common.exception.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service @Transactional(readOnly=true)
public class GroupService {
    private final BookingRepository repo;
    private final BookingService bookings;
    private final GroupExpiration expiration;
    private final CheckinQr qr;
    private final Clock clock;
    private final String secret;
    private final String publicAppUrl;
    private final int deadlineSeconds,minLeadSeconds;
    public GroupService(BookingRepository repo,BookingService bookings,GroupExpiration expiration,CheckinQr qr,Clock clock,
        @Value("${JWT_SECRET}") String secret,@Value("${booking.group.deadline-seconds:1800}") int deadlineSeconds,
        @Value("${booking.group.min-lead-seconds:7200}") int minLeadSeconds,@Value("${APP_PUBLIC_URL:http://localhost:3000}") String publicAppUrl) {
        if(deadlineSeconds<60||minLeadSeconds<deadlineSeconds)throw new IllegalArgumentException("Group deadline must fit inside minimum booking lead time");
        this.repo=repo;this.bookings=bookings;this.expiration=expiration;this.qr=qr;this.clock=clock;
        this.secret=secret;this.deadlineSeconds=deadlineSeconds;this.minLeadSeconds=minLeadSeconds;this.publicAppUrl=publicAppUrl.replaceAll("/+$","");
    }
    @Transactional public Group create(CreateGroup input,String key,Caller caller,String token) {
        caller.requireRole("CUSTOMER");checkKey(key);repo.lock(caller.id());
        String fingerprint=Signatures.sha256(repo.write(input));
        var prior=repo.jdbc().queryForList("SELECT fingerprint,resource_id FROM request_idempotency WHERE actor_id=? AND operation='GROUP' AND key=?",caller.id(),key);
        if(!prior.isEmpty()) {
            if(!prior.get(0).get("fingerprint").equals(fingerprint))throw new ConflictException("Group key was used with different input");
            return load((UUID)prior.get(0).get("resource_id"));
        }
        var hold=bookings.ownHold(input.holdId(),caller);
        if(hold.startsAt().isBefore(clock.instant().plusSeconds(minLeadSeconds)))
            throw new ConflictException("Group booking requires at least "+minLeadSeconds/3600+" hours before play");
        var booking=bookings.create(new CreateInput(hold.id(),null,null),Signatures.sha256("GROUP|"+key),caller,token,false);
        Instant deadline=clock.instant().plusSeconds(deadlineSeconds);
        repo.jdbc().update("UPDATE slot_reservations SET expires_at=? WHERE id=?",Timestamp.from(deadline),hold.id());
        repo.jdbc().update("UPDATE bookings SET hold_expires_at=? WHERE id=?",Timestamp.from(deadline),booking.id());
        String name=input.name()==null||input.name().isBlank()?"Nhóm đặt sân":input.name().trim();
        repo.jdbc().update("INSERT INTO booking_groups(id,owner_id,name,state,deadline,max_members) VALUES(?,?,?,'GROUP_PENDING',?,?)",booking.id(),caller.id(),name,Timestamp.from(deadline),input.maxMembers());
        addMember(booking.id(),caller);
        repo.jdbc().update("INSERT INTO request_idempotency(actor_id,operation,key,fingerprint,resource_id) VALUES(?,'GROUP',?,?,?)",caller.id(),key,fingerprint,booking.id());
        repo.history(booking.id(),caller.id(),"GROUP_CREATED",Map.of("deadline",deadline));return load(booking.id());
    }
    public List<Group> mine(Caller caller) {
        return repo.jdbc().queryForList("SELECT group_id FROM group_members WHERE user_id=? AND active ORDER BY joined_at DESC LIMIT 100",caller.id())
            .stream().map(row->load((UUID)row.get("group_id"))).toList();
    }
    public Group detail(UUID id,Caller caller) {var group=load(id);member(group,caller);return group;}
    public Invite invite(UUID id,Caller caller) {
        var group=load(id);owner(group,caller);pending(group);
        if(group.allocationsLocked())throw new ConflictException("Membership has been finalized");
        String raw=id+"|"+group.deadline().getEpochSecond();
        String code=Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8))+"."+Signatures.hmac(secret,"GROUP|"+raw);
        String path="/customer/groups/join?code="+code;
        return new Invite(code,path,qr.svg(publicAppUrl+path),qr.pngDataUrl(publicAppUrl+path),group.deadline());
    }
    public Group invitation(String code,Caller caller) {caller.requireRole("CUSTOMER");var group=load(invitedId(code));pending(group);return group;}
    @Transactional public Group join(JoinGroup input,Caller caller) {
        caller.requireRole("CUSTOMER");UUID id=invitedId(input.code());lock(id);var group=load(id);pending(group);
        if(group.members().stream().anyMatch(m->m.active()&&m.userId().equals(caller.id())))return group;
        if(group.allocationsLocked()||group.members().size()>=group.maxMembers())throw new ConflictException("Group is full or membership has been finalized");
        var former=repo.jdbc().queryForList("SELECT id FROM group_members WHERE group_id=? AND user_id=?",id,caller.id());
        if(former.isEmpty())addMember(id,caller);
        else repo.jdbc().update("UPDATE group_members SET active=TRUE,display_name=? WHERE id=?",caller.fullName(),former.get(0).get("id"));
        repo.history(id,caller.id(),"GROUP_MEMBER_JOINED",Map.of());return load(id);
    }
    @Transactional public Group leave(UUID id,Caller caller) {
        lock(id);var group=load(id);pending(group);var member=member(group,caller);
        if(group.ownerId().equals(caller.id()))throw new ConflictException("Booking Owner must cancel the group instead");
        mutable(group);
        repo.jdbc().update("UPDATE group_members SET active=FALSE WHERE id=?",member.id());resetAllocation(id);
        repo.history(id,caller.id(),"GROUP_MEMBER_LEFT",Map.of());return load(id);
    }
    @Transactional public Group split(UUID id,Split input,Caller caller) {
        lock(id);var group=load(id);owner(group,caller);pending(group);mutable(group);
        Map<UUID,BigDecimal> amounts=new HashMap<>();BigDecimal total=group.booking().amount();
        if(!group.booking().currency().equals("VND")||total.stripTrailingZeros().scale()>0)throw new ConflictException("Group split requires integral VND amounts");
        if(input.mode().equals("EQUAL")) {
            BigDecimal base=total.divide(BigDecimal.valueOf(group.members().size()),0,RoundingMode.DOWN);
            BigDecimal remainder=total.subtract(base.multiply(BigDecimal.valueOf(group.members().size())));
            for(var m:group.members())amounts.put(m.id(),base.add(m.userId().equals(group.ownerId())?remainder:BigDecimal.ZERO));
        } else {
            if(input.allocations()==null)throw new IllegalArgumentException("Custom allocations are required");
            for(var a:input.allocations()) {
                if(a.amount().signum()<0||a.amount().stripTrailingZeros().scale()>0||amounts.put(a.memberId(),a.amount())!=null)
                    throw new IllegalArgumentException("Allocations must be unique, non-negative integral VND amounts");
            }
        }
        if(amounts.size()!=group.members().size()||group.members().stream().anyMatch(m->!amounts.containsKey(m.id()))
            ||amounts.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(total)!=0)
            throw new ConflictException("Allocations must include every member and equal the immutable booking price exactly");
        for(var m:group.members())repo.jdbc().update("UPDATE group_members SET amount_due=?,amount_paid=0,payment_state='WAITING_FOR_PAYMENT' WHERE id=?",amounts.get(m.id()),m.id());
        repo.jdbc().update("UPDATE booking_groups SET allocations_locked=TRUE WHERE id=?",id);
        repo.history(id,caller.id(),"GROUP_COST_SPLIT",Map.of("mode",input.mode(),"allocations",amounts));return load(id);
    }
    @Transactional public Payable payable(UUID id,UUID memberId,Caller caller) {
        lock(id);var group=load(id);pending(group);
        var member=group.members().stream().filter(m->m.id().equals(memberId)).findFirst().orElseThrow(()->new ResourceNotFoundException("Group member not found"));
        if(!member.userId().equals(caller.id())&&!group.ownerId().equals(caller.id()))throw new ForbiddenException("Only the member or Booking Owner may pay this contribution");
        if(!group.allocationsLocked()||member.amountDue().signum()<=0||member.amountPaid().signum()>0)throw new ConflictException("Contribution is not payable");
        // Prevent an in-flight provider payment from racing a new allocation or member removal.
        repo.jdbc().update("UPDATE group_members SET payment_requested_at=COALESCE(payment_requested_at,?) WHERE id=?",Timestamp.from(clock.instant()),memberId);
        return new Payable(id,caller.id(),member.amountDue(),group.booking().currency(),group.deadline(),"GROUP_CONTRIBUTION",memberId);
    }
    @Transactional public Group cancel(UUID id,CancelInput input,Caller caller) {
        lock(id);var group=load(id);owner(group,caller);
        if(group.state().equals("GROUP_CANCELLED"))return group;
        pending(group);mutable(group);
        repo.jdbc().update("UPDATE booking_groups SET state='GROUP_CANCELLED' WHERE id=?",id);
        repo.jdbc().update("UPDATE bookings SET status='CANCELLED',version=version+1,updated_at=NOW() WHERE id=?",id);
        release(id);repo.history(id,caller.id(),"GROUP_CANCELLED",Map.of("reason",input.reason()));return load(id);
    }
    /** Called in the same court-locked inbox transaction as the payment consumer. */
    public void paid(UUID id,UUID payment,UUID payer,JsonNode payload) {
        var group=load(id);UUID memberId=UUID.fromString(payload.path("memberId").asText());
        if(repo.jdbc().queryForObject("SELECT count(*) FROM group_contributions WHERE payment_id=?",Integer.class,payment)>0)return;
        var member=group.members().stream().filter(m->m.id().equals(memberId)).findFirst().orElse(null);
        Instant paidAt=Instant.parse(payload.path("paidAt").asText());String reason=null;
        if(member==null||(!member.userId().equals(payer)&&!group.ownerId().equals(payer)))reason="GROUP_PAYER_MISMATCH";
        else if(!group.allocationsLocked()||member.amountDue().compareTo(payload.path("amount").decimalValue())!=0||!group.booking().currency().equals(payload.path("currency").asText()))reason="GROUP_AMOUNT_MISMATCH";
        else if(!group.state().equals("GROUP_PENDING")||!group.booking().status().equals("PENDING")||!clock.instant().isBefore(group.deadline())||!paidAt.isBefore(group.deadline()))reason="GROUP_LATE_PAYMENT";
        else if(member.amountPaid().signum()>0)reason="GROUP_DUPLICATE_CONTRIBUTION";
        if(reason!=null) {
            if(repo.jdbc().update("INSERT INTO payment_reconciliation(id,booking_id,payment_id,reason) VALUES(?,?,?,?) ON CONFLICT(payment_id) DO NOTHING",UUID.randomUUID(),id,payment,reason)>0)
                expiration.requestReview(id,payment,payer,reason);
            repo.history(id,payer,"GROUP_PAYMENT_REQUIRES_REVIEW",Map.of("paymentId",payment,"reason",reason));return;
        }
        repo.jdbc().update("INSERT INTO group_contributions(payment_id,group_id,member_id,payer_id,amount,paid_at) VALUES(?,?,?,?,?,?)",payment,id,memberId,payer,member.amountDue(),Timestamp.from(paidAt));
        repo.jdbc().update("UPDATE group_members SET amount_paid=amount_due,payment_state=? WHERE id=?",member.userId().equals(payer)?"PAID":"PAID_BY_OWNER",memberId);
        repo.history(id,payer,"GROUP_CONTRIBUTION_PAID",Map.of("paymentId",payment,"memberId",memberId,"amount",member.amountDue()));
        if(load(id).totalPaid().compareTo(group.booking().amount())==0) {
            repo.jdbc().update("UPDATE booking_groups SET state='CONFIRMED' WHERE id=?",id);
            repo.jdbc().update("UPDATE bookings SET status='CONFIRMED',paid_at=?,checkin_token_version=checkin_token_version+1,version=version+1,updated_at=NOW() WHERE id=?",Timestamp.from(paidAt),id);
            repo.jdbc().update("UPDATE slot_reservations SET state='BOOKED' WHERE id=(SELECT reservation_id FROM bookings WHERE id=?)",id);
            repo.history(id,payer,"GROUP_CONFIRMED",Map.of("totalPaid",group.booking().amount()));
        }
    }
    private Group load(UUID id) {
        var rows=repo.jdbc().queryForList("SELECT * FROM booking_groups WHERE id=?",id);
        if(rows.isEmpty())throw new ResourceNotFoundException("Group not found");var row=rows.get(0);
        var members=repo.jdbc().query("SELECT * FROM group_members WHERE group_id=? AND active ORDER BY joined_at,id",(r,n)->
            new Member(r.getObject("id",UUID.class),r.getObject("user_id",UUID.class),r.getString("display_name"),r.getBoolean("active"),r.getBigDecimal("amount_due"),r.getBigDecimal("amount_paid"),r.getString("payment_state"),r.getTimestamp("payment_requested_at")==null?null:r.getTimestamp("payment_requested_at").toInstant()),id);
        return new Group(id,(UUID)row.get("owner_id"),(String)row.get("name"),(String)row.get("state"),((Timestamp)row.get("deadline")).toInstant(),(Integer)row.get("max_members"),(Boolean)row.get("allocations_locked"),repo.find(id),members,members.stream().map(Member::amountPaid).reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    private void addMember(UUID id,Caller caller){repo.jdbc().update("INSERT INTO group_members(id,group_id,user_id,display_name) VALUES(?,?,?,?)",UUID.randomUUID(),id,caller.id(),caller.fullName());}
    private void lock(UUID id){repo.lock(repo.find(id).courtId());}
    private void owner(Group group,Caller caller){if(!group.ownerId().equals(caller.id()))throw new ForbiddenException("Only Booking Owner may manage this group");}
    private Member member(Group group,Caller caller){return group.members().stream().filter(m->m.userId().equals(caller.id())).findFirst().orElseThrow(()->new ForbiddenException("Group membership is required"));}
    private void pending(Group group){if(!group.state().equals("GROUP_PENDING")||!group.booking().status().equals("PENDING")||!clock.instant().isBefore(group.deadline()))throw new ConflictException("Group payment deadline has ended or group is no longer pending");}
    private void mutable(Group group){if(group.members().stream().anyMatch(m->m.amountPaid().signum()>0||m.paymentRequestedAt()!=null))throw new ConflictException("BLOCKED_RULE: membership or allocations cannot change after payment has started");}
    private void resetAllocation(UUID id){repo.jdbc().update("UPDATE group_members SET amount_due=0,amount_paid=0,payment_state='UNPAID' WHERE group_id=?",id);repo.jdbc().update("UPDATE booking_groups SET allocations_locked=FALSE WHERE id=?",id);}
    private void release(UUID id){repo.jdbc().update("UPDATE slot_reservations SET state='RELEASED' WHERE id=(SELECT reservation_id FROM bookings WHERE id=?)",id);}
    private UUID invitedId(String code) {
        try {
            String[] parts=code.split("\\.");if(parts.length!=2)throw new IllegalArgumentException();
            String raw=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
            if(!Signatures.matches(Signatures.hmac(secret,"GROUP|"+raw),parts[1]))throw new IllegalArgumentException();
            String[] fields=raw.split("\\|");if(fields.length!=2||!Instant.ofEpochSecond(Long.parseLong(fields[1])).isAfter(clock.instant()))throw new IllegalArgumentException();
            return UUID.fromString(fields[0]);
        } catch(Exception ex){throw new ForbiddenException("Group invitation is invalid or expired");}
    }
    private void checkKey(String key){if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,100}"))throw new IllegalArgumentException("A valid Idempotency-Key is required");}
}
