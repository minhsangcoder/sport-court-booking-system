package com.sporthub.booking.service;

import static com.sporthub.booking.web.BookingDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookingService {
  private final BookingHolds holds;
  private final BookingCreation creation;
  private final BookingReads reads;
  private final BookingUsage usage;
  private final BookingAvailabilityService availability;
  private final HoldProtection protection;

  @org.springframework.beans.factory.annotation.Autowired
  public BookingService(
      BookingHolds holds,
      BookingCreation creation,
      BookingReads reads,
      BookingUsage usage,
      BookingAvailabilityService availability,
      HoldProtection protection) {
    this.holds = holds;
    this.creation = creation;
    this.reads = reads;
    this.usage = usage;
    this.availability = availability;
    this.protection = protection;
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  public Hold hold(HoldInput input, String key, Caller caller, String token, boolean counter) {
    var replay = holds.replayHold(input, key, caller, token, counter);
    if (replay != null) return replay;
    if (!counter) protection.prepare(caller.id());
    return holds.hold(input, key, caller, token, counter);
  }

  public Hold ownHold(UUID id, Caller caller, String token) {
    return holds.ownHold(id, caller, token);
  }

  @Transactional
  public void releaseHold(UUID id, Caller caller, String token) {
    holds.releaseHold(id, caller, token);
  }

  @Transactional
  public Booking create(
      CreateInput input, String key, Caller caller, String token, boolean counter) {
    return creation.create(input, key, caller, token, counter);
  }

  @Transactional
  public Booking counter(CounterInput input, String key, Caller caller, String token) {
    return creation.counter(input, key, caller, token);
  }

  public List<Booking> mine(Caller caller, String token) {
    return reads.mine(caller, token);
  }

  public List<Booking> facility(UUID id, Caller caller, String token) {
    return reads.facility(id, caller, token);
  }

  public Detail detail(UUID id, Caller caller, String token) {
    return reads.detail(id, caller, token);
  }

  public Booking projectEffective(Booking b) {
    return reads.projectEffective(b);
  }

  public Payable payable(UUID id, Caller caller, String token) {
    return reads.payable(id, caller, token);
  }

  @Transactional
  public Booking cancel(UUID id, CancelInput input, Caller caller, String token) {
    return usage.cancel(id, input, caller, token);
  }

  @Transactional
  public Booking checkin(UUID id, CheckinInput input, Caller caller, String token) {
    return usage.checkin(id, input, caller, token);
  }

  @Transactional
  public Booking checkinByCode(UUID facility, CodeInput input, Caller caller, String token) {
    return usage.checkinByCode(facility, input, caller, token);
  }

  @Transactional
  public Booking complete(UUID id, Caller caller, String token) {
    return usage.complete(id, caller, token);
  }

  @Transactional
  public Booking checkinGuest(UUID id, Caller caller, String token) {
    return usage.checkinGuest(id, caller, token);
  }

  public JsonNode availability(UUID court, String date) {
    return availability.availability(court, date);
  }

  @Transactional
  public void expireCourt(UUID court) {
    holds.expireCourt(court);
  }
}
