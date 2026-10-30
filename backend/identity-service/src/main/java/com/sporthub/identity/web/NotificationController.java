package com.sporthub.identity.web;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.identity.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
/** In-app inbox over the notification records; every statement is scoped to the signed-in user. */
@RestController @RequestMapping("/api/v1/users/me/notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;
    public NotificationController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping public ApiResponse<Inbox> mine(@AuthenticationPrincipal AuthenticatedUser principal){
        var items=jdbc.query("SELECT id,subject,body,link_path,created_at,read_at FROM identity_notifications WHERE user_id=? ORDER BY created_at DESC,id LIMIT 30",
            (r,n)->new Item(r.getObject("id",UUID.class),r.getString("subject"),r.getString("body").replaceAll("(?m)^https?://\\S+$","").strip(),r.getString("link_path"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("read_at")==null?null:r.getTimestamp("read_at").toInstant()),principal.userId());
        return ApiResponse.success(new Inbox(jdbc.queryForObject("SELECT count(*) FROM identity_notifications WHERE user_id=? AND read_at IS NULL",Integer.class,principal.userId()),items));
    }
    @PostMapping("/{id}/read") public ApiResponse<Void> read(@PathVariable UUID id,@AuthenticationPrincipal AuthenticatedUser principal){
        jdbc.update("UPDATE identity_notifications SET read_at=NOW() WHERE id=? AND user_id=? AND read_at IS NULL",id,principal.userId());
        return ApiResponse.success(null);
    }
    @PostMapping("/read-all") public ApiResponse<Void> readAll(@AuthenticationPrincipal AuthenticatedUser principal){
        jdbc.update("UPDATE identity_notifications SET read_at=NOW() WHERE user_id=? AND read_at IS NULL",principal.userId());
        return ApiResponse.success(null);
    }
    public record Item(UUID id,String subject,String body,String link,Instant createdAt,Instant readAt){}
    public record Inbox(int unreadCount,List<Item> items){}
}
