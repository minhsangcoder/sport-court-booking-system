package com.sporthub.facility.service;

import static com.sporthub.facility.web.FacilityDtos.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Bulk public projections: no lazy entity traversal or per-court database reads. */
@Service
@Transactional(readOnly = true)
public class DiscoveryCatalog {
    public record Entry(FacilityView facility, List<CourtView> courts, String imageUrl) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final OwnerApplicationGate gate;
    private final MediaService media;
    public DiscoveryCatalog(JdbcTemplate jdbc, OwnerApplicationGate gate, MediaService media) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc); this.gate = gate; this.media = media;
    }
    public List<Entry> search(String q, String provinceCode, String wardCode, UUID category, UUID facilityId) {
        return load(q, provinceCode, wardCode, category, facilityId, null);
    }
    private List<Entry> load(String q, String provinceCode, String wardCode, UUID category, UUID facilityId, List<UUID> courtIds) {
        if(q != null && q.length() > 180) throw new IllegalArgumentException("Search text must not exceed 180 characters");
        var args = new HashMap<String,Object>();
        args.put("q", "%" + (q == null ? "" : q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%");
        args.put("province", provinceCode); args.put("ward", wardCode); args.put("category", category); args.put("facility", facilityId);
        String scope = courtIds == null ? "" : " AND c.id IN (:courtIds)";
        if(courtIds != null) args.put("courtIds", courtIds);
        var rows = jdbc.queryForList("""
            SELECT f.*, a.application_id FROM facilities f
            LEFT JOIN first_facility_applications a ON a.facility_id=f.id
            WHERE f.status='ACTIVE' AND lower(concat_ws(' ',f.name,f.address_line,f.province,f.ward)) LIKE :q
            AND (:province::varchar IS NULL OR f.province_code=:province::varchar)
            AND (:ward::varchar IS NULL OR f.ward_code=:ward::varchar)
            AND (:facility::uuid IS NULL OR f.id=:facility::uuid)
            AND EXISTS(SELECT 1 FROM courts c JOIN sport_categories s ON s.id=c.sport_category_id
                WHERE c.facility_id=f.id AND c.enabled AND s.active
                AND (:category::uuid IS NULL OR c.sport_category_id=:category::uuid)
            """ + scope + ") ORDER BY f.name,f.id", args);
        var applicationIds = rows.stream().map(r -> (UUID)r.get("application_id")).filter(Objects::nonNull).toList();
        var approved = new HashSet<UUID>();
        for(int offset=0;offset<applicationIds.size();offset+=200)
            approved.addAll(gate.approved(applicationIds.subList(offset,Math.min(offset+200,applicationIds.size()))));
        rows = rows.stream().filter(r -> r.get("application_id") == null || approved.contains(r.get("application_id"))).toList();
        if(rows.isEmpty()) return List.of();
        var ids = rows.stream().map(r -> (UUID)r.get("id")).toList(); args.put("ids", ids);
        var courts = jdbc.query("""
            SELECT c.* FROM courts c JOIN sport_categories s ON s.id=c.sport_category_id
            WHERE c.facility_id IN (:ids) AND c.enabled AND s.active
            AND (:category::uuid IS NULL OR c.sport_category_id=:category::uuid)
            """ + scope + " ORDER BY c.name,c.id", args, (r,n) -> new CourtView(r.getObject("id", UUID.class), r.getObject("facility_id", UUID.class),
                r.getObject("sport_category_id", UUID.class), r.getString("code"), r.getString("name"), r.getString("description"), r.getBoolean("enabled"), r.getLong("version")));
        var byFacility = courts.stream().collect(java.util.stream.Collectors.groupingBy(CourtView::facilityId));
        var amenities = new HashMap<UUID,Set<String>>();
        jdbc.query("SELECT facility_id,name FROM facility_amenities WHERE facility_id IN (:ids)", args, r -> {
            amenities.computeIfAbsent(r.getObject("facility_id", UUID.class), k -> new LinkedHashSet<>()).add(r.getString("name"));
        });
        var images = media.coverImages(ids);
        return rows.stream().map(r -> {
            UUID id = (UUID)r.get("id");
            var f = new FacilityView(id,(UUID)r.get("owner_id"),(String)r.get("name"),(String)r.get("phone"),(String)r.get("address_line"),
                (String)r.get("province_code"),(String)r.get("province"),(String)r.get("ward_code"),(String)r.get("ward"),
                (String)r.get("description"),(String)r.get("timezone"),(java.math.BigDecimal)r.get("latitude"),(java.math.BigDecimal)r.get("longitude"),
                (String)r.get("status"),amenities.getOrDefault(id,Set.of()),((Number)r.get("version")).longValue());
            return new Entry(f,byFacility.getOrDefault(id,List.of()), images.get(id));
        }).toList();
    }
    public List<CourtContext> contexts(List<UUID> ids) {
        if(ids == null || ids.isEmpty() || ids.size() > 200) throw new IllegalArgumentException("Supply 1–200 court IDs");
        var entries = load(null,null,null,null,null,ids);
        var windows = jdbc.query("SELECT * FROM maintenance_windows WHERE court_id IN (:ids) AND NOT cancelled", Map.of("ids",ids),
            (r,n) -> new MaintenanceView(r.getObject("id",UUID.class),r.getObject("court_id",UUID.class),r.getTimestamp("starts_at").toInstant(),r.getTimestamp("ends_at").toInstant(),r.getString("reason"),false));
        var byCourt = windows.stream().collect(java.util.stream.Collectors.groupingBy(MaintenanceView::courtId));
        return entries.stream().flatMap(e -> e.courts().stream().map(c -> new CourtContext(e.facility(),c,byCourt.getOrDefault(c.id(),List.of())))).toList();
    }
}
