package com.sporthub.booking.service;
import com.sporthub.booking.repository.BookingRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;
@Component
public class BookingExpiry {
    private final BookingRepository repo;private final BookingService service;
    public BookingExpiry(BookingRepository repo,BookingService service){this.repo=repo;this.service=service;}
    @Scheduled(fixedDelayString="${booking.expiry-delay-ms:2000}",initialDelayString="${booking.expiry-delay-ms:2000}") public void expire(){for(var court:repo.jdbc().queryForList("SELECT DISTINCT court_id FROM slot_reservations WHERE state='HOLD' AND expires_at<=NOW()",UUID.class))service.expireCourt(court);}
}
