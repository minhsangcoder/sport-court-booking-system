package com.sporthub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.service.DiscoveryService;
import com.sporthub.booking.service.BookingDemoFinance;
import com.sporthub.common.money.BookingPaymentFingerprint;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in only: owns its Testcontainers database; the inherited business fixtures keep all triggers active. */
@EnabledIfSystemProperty(named = "sporthub.performance", matches = "true")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LoadingPerformanceTest extends BookingIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
  private static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test void measure() throws Exception {
    assertThat(System.getenv("SPORTHUB_BOOKING_TEST_DB_URL")).as("Never benchmark an externally supplied database").isNull();
    var output = new LinkedHashMap<String, Object>();
    output.put("runtime", Map.of("java", System.getProperty("java.version"), "warmups", 10, "samples", 30,
        "clock", now.toString(), "dependencyLatencyMs", 1, "transport", "MockMvc HTTP controller + Spring + PostgreSQL, deterministic dependency stubs; excludes socket latency"));
    var small = seedDataset("small", 16);
    var large = seedDataset("large", 1200);
    for (var fixture : List.of(small, large)) {
      var data = new LinkedHashMap<String, Object>();
      data.put("bookings", fixture.count());
      data.put("search", timed(() -> request("/api/v1/bookings/search?q=" + fixture.name() + "&date=2026-10-05&sort=NAME")));
      clearInvocations(dependencies, discoveryDependencies);
      discovery.search(fixture.criteria());
      data.put("searchDependencyCalls", Map.of("preview", mockingDetails(dependencies).getInvocations().stream().filter(i -> i.getMethod().getName().equals("preview")).count(),
          "facility", mockingDetails(discoveryDependencies).getInvocations().size()));
      when(identity.current(any())).thenReturn(fixture.owner());
      data.put("report", timed(() -> request("/api/v1/bookings/reports/facility/" + fixture.facility() + "/dashboard?fromDate=2026-10-05&toDate=2026-10-05&page=0&size=25")));
      data.put("reservationPlan", repo.jdbc().queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT starts_at,ends_at,state FROM slot_reservations WHERE court_id=? AND (state='BOOKED' OR (state='HOLD' AND expires_at>?))", fixture.court(), Timestamp.from(now)));
      data.put("bookingPlan", repo.jdbc().queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT * FROM bookings WHERE facility_id=?", fixture.facility()));
      data.put("reservationRowsWithoutRange", repo.jdbc().queryForObject("SELECT count(*) FROM slot_reservations WHERE court_id=? AND state='BOOKED'", Long.class, fixture.court()));
      compareExecutor(fixture,data);
      var from=Timestamp.from(now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
      var to=Timestamp.from(from.toInstant().plusSeconds(86400));
      data.put("reservationRangePlan",repo.jdbc().queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT starts_at,ends_at,state FROM slot_reservations WHERE court_id=? AND state IN('BOOKED','HOLD') AND tstzrange(starts_at,ends_at,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)') AND (state='BOOKED' OR expires_at>?)",fixture.court(),from,to,Timestamp.from(now)));
      data.put("reservationRowsWithRange",repo.jdbc().queryForObject("SELECT count(*) FROM slot_reservations WHERE court_id=? AND state IN('BOOKED','HOLD') AND tstzrange(starts_at,ends_at,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)') AND (state='BOOKED' OR expires_at>?)",Long.class,fixture.court(),from,to,Timestamp.from(now)));
      data.put("eventRangePlan",repo.jdbc().queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT e.*,b.court_id,b.source FROM demo_money_events e JOIN bookings b ON b.id=e.booking_id WHERE e.facility_id=? AND e.performed_at>=? AND e.performed_at<?",fixture.facility(),from,to));
      data.put("settlementRangePlan",repo.jdbc().queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT s.*,h.paid_at purchase_paid_at FROM demo_transfer_settlements s JOIN transfer_handoffs h ON h.acquisition_id=s.acquisition_id WHERE s.facility_id=? AND ((s.handoff_at>=? AND s.handoff_at<?) OR (h.paid_at>=? AND h.paid_at<?) OR(s.handoff_at<? AND(s.paid_at IS NULL OR s.paid_at>=?)))",fixture.facility(),from,to,from,to,Timestamp.from(now),Timestamp.from(now)));
      reportRows(fixture,data);
      output.put(fixture.name(), data);
    }
    // Money fixtures use the actual lifecycle, decision protocol, immutable proofs and failed executions.
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 500, true);
    demoReportAccess(deal.owner());
    demoFinance.paySeller(deal.command().acquisitionId(), new BookingDemoFinance.Outcome("FAILED"), key(), deal.owner(), "token");
    seedHistoricalCancellation(repo.find(b.id()), deal.buyer(), "Synthetic performance cancellation");
    output.put("moneyScenario", demoReports.dashboard(facility, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), 0, 25, deal.owner(), "token"));
    var destination = Path.of(System.getProperty("sporthub.performance.output", "target/loading-performance.json"));
    Files.createDirectories(destination.toAbsolutePath().getParent());
    Files.writeString(destination, json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM bookings", Integer.class)).isGreaterThanOrEqualTo(1217);
    System.out.println("PERFORMANCE_RESULT=" + destination.toAbsolutePath());
  }

  private JsonNode request(String url) {
    try {
      var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization", "synthetic-token")).andReturn().getResponse();
      assertThat(response.getStatus()).isEqualTo(200);
      return json.readTree(response.getContentAsString()).path("data");
    } catch (Exception error) { throw new IllegalStateException(error); }
  }

  private Map<String, Object> timed(Supplier<Object> action) throws Exception {
    for (int i = 0; i < 10; i++) action.get();
    var samples = new ArrayList<Double>();
    Object payload = null;
    for (int i = 0; i < 30; i++) {
      long start = System.nanoTime();
      payload = action.get();
      samples.add((System.nanoTime() - start) / 1_000_000.0);
    }
    var sorted = samples.stream().sorted().toList();
    return Map.of("p50Ms", sorted.get(14), "p95Ms", sorted.get(28), "samplesMs", samples, "payload", payload);
  }

  private void compareExecutor(Dataset fixture,Map<String,Object> data) throws Exception {
    Class<?> type;
    try{type=Class.forName("com.sporthub.booking.service.DiscoveryExecutor");}catch(ClassNotFoundException before){return;}
    var planner=Class.forName("com.sporthub.booking.service.DiscoveryPlanner");var mapper=Class.forName("com.sporthub.booking.service.DiscoveryResultMapper");
    try(var executor=(AutoCloseable)type.getConstructor(int.class).newInstance(1)){
      var sequential=DiscoveryService.class.getConstructor(planner,mapper,type).newInstance(context.getBean(planner),context.getBean(mapper),executor);
      var a=timed(()->sequential.search(fixture.criteria()));var b=timed(()->discovery.search(fixture.criteria()));
      assertThat(a.get("payload")).isEqualTo(b.get("payload"));data.put("executorSequential",a);data.put("executorParallel",b);
    }
  }
  private void reportRows(Dataset fixture,Map<String,Object> data)throws Exception{
    Class<?> type;
    try{type=Class.forName("com.sporthub.booking.service.ReportSnapshotReader");}catch(ClassNotFoundException before){return;}
    var window=com.sporthub.common.time.FacilityReportWindow.of(LocalDate.of(2026,10,5),LocalDate.of(2026,10,5),"Asia/Ho_Chi_Minh",clock);
    var method=type.getDeclaredMethod("read",UUID.class,com.sporthub.common.time.FacilityReportWindow.class,Instant.class);method.setAccessible(true);
    var snapshot=method.invoke(context.getBean(type),fixture.facility(),window,now);var count=new LinkedHashMap<String,Integer>();
    for(String accessor:List.of("bookings","collections","events","settlements","refunds")){var get=snapshot.getClass().getDeclaredMethod(accessor);get.setAccessible(true);count.put(accessor,((Collection<?>)get.invoke(snapshot)).size());}
    data.put("reportSnapshotRows",count);
    var source=Files.readString(Path.of("src/main/java/com/sporthub/booking/service/ReportSnapshotReader.java"));
    int start=source.indexOf("WITH relevant AS"),end=source.indexOf("\"\"\"",start);
    var sql=source.substring(start,end);
    var from=Timestamp.from(window.from());var to=Timestamp.from(window.to());
    data.put("reportCandidateSql",sql);
    data.put("reportCandidatePlan",repo.jdbc().queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+sql,fixture.facility(),from,to,from,to,to,from,fixture.facility(),from,to,fixture.facility(),from,to,fixture.facility(),"{}"));
  }

  record Dataset(String name, int count, UUID facility, UUID court, com.sporthub.common.security.RemoteIdentity.Caller owner, DiscoveryService.Criteria criteria) {}

  private Dataset seedDataset(String name, int count) {
    UUID facilityId = stable(name + "-facility-0"), courtId = stable(name + "-court-0"), category = stable("sport");
    var owner = new com.sporthub.common.security.RemoteIdentity.Caller(stable(name + "-owner"), "Synthetic Owner", Set.of("OWNER"), Map.of());
    var facilities = json.createArrayNode();
    for (int f = 0; f < 4; f++) {
      UUID fid = stable(name + "-facility-" + f);
      facilities.add(json.createObjectNode().put("id", fid.toString()).put("name", "Facility " + f).put("timezone", "Asia/Ho_Chi_Minh").put("province", "Synthetic"));
      var courts = json.createArrayNode();
      for (int c = 0; c < 2; c++) {
        UUID cid = stable(name + "-court-" + (f * 2 + c));
        courts.add(json.createObjectNode().put("id", cid.toString()).put("name", "Court " + c).put("enabled", true).put("sportCategoryId", category.toString()));
        var preview = json.createObjectNode().put("facilityId", fid.toString()).put("timezone", "Asia/Ho_Chi_Minh");
        preview.putArray("slots").add(json.createObjectNode().put("state", "ELIGIBLE").put("amount", 120000).put("currency", "VND").put("startsAt", start.toString()).put("endsAt", end.toString()));
        when(dependencies.preview(eq(cid), anyString())).thenAnswer(a -> { Thread.sleep(1); return preview; });
      }
      when(discoveryDependencies.courts(fid)).thenReturn(courts);
    }
    when(discoveryDependencies.categories()).thenReturn(json.createArrayNode().add(json.createObjectNode().put("id", category.toString()).put("name", "Synthetic Sport")));
    when(discoveryDependencies.facilities(eq(name), isNull(), isNull())).thenReturn(facilities);
    when(dependencies.ownerFacility(eq(facilityId), eq(owner), anyString())).thenReturn(json.createObjectNode().put("id", facilityId.toString()).put("ownerId", owner.id().toString()).put("timezone", "Asia/Ho_Chi_Minh"));
    when(dependencies.ownerCourts(eq(facilityId), anyString())).thenReturn(json.createArrayNode().add(json.createObjectNode().put("id", courtId.toString()).put("name", "Court 0")));
    new TransactionTemplate(txManager).executeWithoutResult(tx -> {
      for (int i = 0; i < count; i++) {
        UUID booking = stable(name + "-booking-" + i), reservation = stable(name + "-reservation-" + i), payment = stable(name + "-payment-" + i);
        UUID cid = stable(name + "-court-" + i % 8), fid = stable(name + "-facility-" + (i % 8) / 2);
        Instant booked = start.minusSeconds((i / 8L + 2) * 86400), paid = booked.minusSeconds(3600);
        BigDecimal amount = new BigDecimal("120000");
        String fingerprint = BookingPaymentFingerprint.of(booking, user.id(), "", "BOOKING", "SUCCESS", amount, "VND", paid);
        repo.jdbc().update("INSERT INTO slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote) VALUES(?,?,?,?,?,?,?,'BOOKED','{}')", reservation, cid, fid, user.id(), Timestamp.from(booked), Timestamp.from(booked.plusSeconds(3600)), Timestamp.from(paid));
        repo.jdbc().update("INSERT INTO bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at,payment_id,paid_at) VALUES(?,?,?,?,?,?,?,?,?,'CONFIRMED','ONLINE',120000,'VND','{}',?,?,?)", booking,reservation,fid,cid,user.id(),user.id(),user.id(),Timestamp.from(booked),Timestamp.from(booked.plusSeconds(3600)),Timestamp.from(paid),payment,Timestamp.from(paid));
        repo.jdbc().update("INSERT INTO booking_payment_receipts(payment_id,booking_id,fingerprint) VALUES(?,?,?)", payment,booking,fingerprint);
        repo.history(booking,user.id(),"CONFIRMED",Map.of("paymentId",payment));
        repo.jdbc().update("INSERT INTO booking_demo_collections(payment_id,booking_id,payer_id,purpose,amount,currency,paid_at,source_event_id,fingerprint) VALUES(?,?,?,'BOOKING',120000,'VND',?,?,?)",payment,booking,user.id(),Timestamp.from(paid),stable(name+"-event-"+i),fingerprint);
      }
    });
    return new Dataset(name,count,facilityId,courtId,owner,new DiscoveryService.Criteria(name,null,null,"2026-10-05",null,null,null,null,null,null,null,"NAME"));
  }
}
