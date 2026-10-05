package com.sporthub.identity.repository;

import com.sporthub.identity.service.OwnerApplicationSearch;
import com.sporthub.identity.web.dto.OwnerApplicationDtos.SearchPage;
import com.sporthub.identity.web.dto.OwnerApplicationDtos.Summary;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OwnerApplicationSearchRepository {
    // Account contact/name are already plaintext Identity fields. Never select/decrypt legal payloads for search.
    private static final String FROM="""
        FROM owner_applications a JOIN users u ON u.id=a.user_id JOIN user_profiles p ON p.user_id=u.id
        WHERE (CAST(:q AS text) IS NULL OR a.business_name ILIKE :q ESCAPE '!' OR a.facility_name ILIKE :q ESCAPE '!'
            OR p.full_name ILIKE :q ESCAPE '!' OR u.phone LIKE :q ESCAPE '!')
        AND (CAST(:state AS text) IS NULL OR a.state=:state)
        AND (CAST(:applicant AS text) IS NULL OR p.full_name ILIKE :applicant ESCAPE '!'
            OR u.email ILIKE :applicant ESCAPE '!' OR u.phone LIKE :applicant ESCAPE '!')
        AND (CAST(:facility AS text) IS NULL OR a.facility_name ILIKE :facility ESCAPE '!')
        AND (CAST(:submittedFrom AS timestamptz) IS NULL OR a.submitted_at>=CAST(:submittedFrom AS timestamptz))
        AND (CAST(:submittedUntil AS timestamptz) IS NULL OR a.submitted_at<CAST(:submittedUntil AS timestamptz))
        AND (CAST(:reviewedFrom AS timestamptz) IS NULL OR a.reviewed_at>=CAST(:reviewedFrom AS timestamptz))
        AND (CAST(:reviewedUntil AS timestamptz) IS NULL OR a.reviewed_at<CAST(:reviewedUntil AS timestamptz))
        AND (CAST(:reviewedBy AS uuid) IS NULL OR a.reviewed_by=CAST(:reviewedBy AS uuid))
        AND (CAST(:applicationId AS uuid) IS NULL OR a.id=CAST(:applicationId AS uuid))
        """;
    private static final String SELECT="SELECT a.id,a.user_id,a.facility_id,a.business_name,a.facility_name,a.state,a.submitted_at,a.reason,a.commission_percent,a.last_error ";
    private static final RowMapper<Summary> SUMMARY=(r,n)->new Summary(r.getObject("id",UUID.class),r.getObject("user_id",UUID.class),
            r.getObject("facility_id",UUID.class),r.getString("business_name"),r.getString("facility_name"),r.getString("state"),
            r.getTimestamp("submitted_at")==null?null:r.getTimestamp("submitted_at").toInstant(),r.getString("reason"),r.getBigDecimal("commission_percent"),r.getString("last_error"));
    private final NamedParameterJdbcTemplate jdbc;
    public OwnerApplicationSearchRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

    public List<Summary> legacy(OwnerApplicationSearch c){return rows(c,parameters(c),0);}

    /** Count and items use one snapshot, including while a decision/resubmission changes the queue. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SearchPage search(OwnerApplicationSearch c){
        var p=parameters(c);long total=jdbc.queryForObject("SELECT COUNT(*) "+FROM,p,Long.class);
        long offset=(long)c.page()*c.size();
        return new SearchPage(offset>=total?List.of():rows(c,p,offset),c.page(),c.size(),total,(total+c.size()-1)/c.size());
    }
    private List<Summary> rows(OwnerApplicationSearch c,MapSqlParameterSource p,long offset){
        String order=c.sort().equals("SUBMITTED_DESC")?" ORDER BY a.submitted_at DESC NULLS LAST,a.created_at DESC,a.id DESC":" ORDER BY a.submitted_at ASC NULLS LAST,a.created_at ASC,a.id ASC";
        return jdbc.query(SELECT+FROM+order+" LIMIT :size OFFSET :offset",p.addValue("size",c.size()).addValue("offset",offset),SUMMARY);
    }
    private MapSqlParameterSource parameters(OwnerApplicationSearch c){
        return new MapSqlParameterSource().addValue("q",pattern(c.q())).addValue("state",c.state()).addValue("applicant",pattern(c.applicant()))
            .addValue("facility",pattern(c.facility())).addValue("submittedFrom",timestamp(c.submittedFrom())).addValue("submittedUntil",timestamp(c.submittedUntil()))
            .addValue("reviewedFrom",timestamp(c.reviewedFrom())).addValue("reviewedUntil",timestamp(c.reviewedUntil()))
            .addValue("reviewedBy",c.reviewedBy()).addValue("applicationId",c.applicationId());
    }
    private Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    private String pattern(String value){return value==null?null:"%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
}
