package com.sporthub.identity.service;

import com.sporthub.identity.exception.IdentityException;
import com.sporthub.identity.web.dto.OwnerApplicationDtos.SearchCriteria;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.Set;
import java.util.UUID;

/** Dates denote Vietnam calendar days, consistent with the Admin reports. */
public record OwnerApplicationSearch(String q,String state,String applicant,String facility,
        Instant submittedFrom,Instant submittedUntil,Instant reviewedFrom,Instant reviewedUntil,
        UUID reviewedBy,UUID applicationId,String sort,int page,int size) {
    private static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> STATES=Set.of("DRAFT","SUBMITTING","PENDING_APPROVAL","SUPPLEMENT_REQUIRED","DECIDING","APPROVING","APPROVED","REJECTED");

    public static OwnerApplicationSearch parse(SearchCriteria c) {
        String state=text(c.state(),30,"state"),sort=text(c.sort(),30,"sort");
        if(state!=null&&!STATES.contains(state))throw invalid("Unknown application state");
        if(sort==null)sort="SUBMITTED_ASC";
        if(!Set.of("SUBMITTED_ASC","SUBMITTED_DESC").contains(sort))throw invalid("Unsupported sort; use SUBMITTED_ASC or SUBMITTED_DESC");
        LocalDate sf=date(c.submittedFrom(),"submittedFrom"),st=date(c.submittedTo(),"submittedTo");
        LocalDate rf=date(c.reviewedFrom(),"reviewedFrom"),rt=date(c.reviewedTo(),"reviewedTo");
        if(sf!=null&&st!=null&&sf.isAfter(st))throw invalid("submittedFrom must be on or before submittedTo");
        if(rf!=null&&rt!=null&&rf.isAfter(rt))throw invalid("reviewedFrom must be on or before reviewedTo");
        return new OwnerApplicationSearch(text(c.q(),180,"q"),state,text(c.applicant(),254,"applicant"),text(c.facility(),180,"facility"),
                start(sf),end(st),start(rf),end(rt),uuid(c.reviewedBy(),"reviewedBy"),uuid(c.applicationId(),"applicationId"),
                sort,number(c.page(),0,0,Integer.MAX_VALUE,"page"),number(c.size(),20,1,100,"size"));
    }
    public static OwnerApplicationSearch legacy(String q,String state) {
        return parse(new SearchCriteria(q,state,null,null,null,null,null,null,null,null,null,"0","100"));
    }
    private static String text(String value,int max,String name) {
        if(value==null||value.isBlank())return null;
        value=value.trim();if(value.length()>max)throw invalid(name+" exceeds "+max+" characters");return value;
    }
    private static LocalDate date(String value,String name) {
        value=text(value,10,name);if(value==null)return null;
        try {if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new DateTimeException("format");
            LocalDate date=LocalDate.parse(value);if(date.getYear()<1)throw new DateTimeException("year");return date;
        } catch(DateTimeException ex){throw invalid(name+" must be a valid YYYY-MM-DD date");}
    }
    private static Instant start(LocalDate date){return date==null?null:date.atStartOfDay(ZONE).toInstant();}
    private static Instant end(LocalDate date){return date==null?null:date.plusDays(1).atStartOfDay(ZONE).toInstant();}
    private static UUID uuid(String value,String name) {
        value=text(value,36,name);if(value==null)return null;
        try {UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException();return id;
        } catch(IllegalArgumentException ex){throw invalid(name+" must be a UUID");}
    }
    private static int number(String value,int fallback,int min,int max,String name) {
        value=text(value,10,name);if(value==null)return fallback;
        try {if(!value.matches("[0-9]+"))throw new NumberFormatException();int result=Integer.parseInt(value);
            if(result<min||result>max)throw new NumberFormatException();return result;
        } catch(NumberFormatException ex){throw invalid(name+" must be an integer between "+min+" and "+max);}
    }
    private static IdentityException invalid(String message){return new IdentityException(HttpStatus.BAD_REQUEST,"IDENTITY-OWNER-APPLICATION",message);}
}
