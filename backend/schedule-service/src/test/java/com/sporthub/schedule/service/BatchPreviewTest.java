package com.sporthub.schedule.service;
import com.sporthub.schedule.repository.ScheduleRepository;
import com.sporthub.schedule.web.ScheduleDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
class BatchPreviewTest {
    @Test void bulkPreviewReadsConfigurationOnceAndPreservesCourtRulesAndMaintenance() {
        var repo=mock(ScheduleRepository.class);var facilities=mock(FacilityClient.class);
        var service=new ScheduleService(repo,facilities,new ObjectMapper(),mock(com.sporthub.common.schedule.ConfigurationChanges.class),Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"),ZoneOffset.UTC),mock(jakarta.validation.Validator.class));
        UUID facility=UUID.randomUUID(),one=UUID.randomUUID(),two=UUID.randomUUID(),sport=UUID.randomUUID();LocalDate day=LocalDate.of(2026,10,13);var zone=ZoneId.of("Asia/Ho_Chi_Minh");
        when(facilities.contexts(List.of(one,two))).thenReturn(List.of(new FacilityClient.Context(facility,one,sport,zone,true,"ACTIVE",List.of()),new FacilityClient.Context(facility,two,sport,zone,true,"ACTIVE",List.of(new FacilityClient.Window(day.atTime(8,0).atZone(zone).toInstant(),day.atTime(9,0).atZone(zone).toInstant())))));
        when(repo.hours(List.of(facility))).thenReturn(List.of(new Hours(UUID.randomUUID(),facility,null,2,LocalTime.of(8,0),LocalTime.of(10,0),60,0)));
        when(repo.exceptions(List.of(facility))).thenReturn(List.of());
        when(repo.rules(List.of(facility))).thenReturn(List.of(new PriceRule(UUID.randomUUID(),facility,null,sport,2,null,LocalTime.of(8,0),LocalTime.of(10,0),new BigDecimal("100"),0,"Base",day,null,"VND",true,0)));
        var result=service.publicPreviews(List.of(one,two),day);assertThat(result).hasSize(2);assertThat(result.getFirst().slots()).allMatch(s -> s.state().equals("ELIGIBLE"));assertThat(result.getLast().slots().getFirst().reason()).isEqualTo("MAINTENANCE");
        verify(repo,times(1)).hours(List.of(facility));verify(repo,times(1)).exceptions(List.of(facility));verify(repo,times(1)).rules(List.of(facility));verify(facilities,never()).context(any(),any());
    }
}
