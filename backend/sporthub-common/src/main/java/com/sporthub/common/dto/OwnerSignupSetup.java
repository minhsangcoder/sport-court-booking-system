package com.sporthub.common.dto;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Restricted initial configuration, shared by the signed signup commands. No identity/state fields. */
public record OwnerSignupSetup(String courtCode,String courtName,UUID sportCategoryId,
        Set<Integer> days,LocalTime opensAt,LocalTime closesAt,int slotMinutes,BigDecimal pricePerSlot) {
    public void validate() {
        if(courtCode==null||courtCode.isBlank()||courtCode.length()>50||courtName==null||courtName.isBlank()||courtName.length()>180||sportCategoryId==null)
            throw new IllegalArgumentException("Choose a sport category and supply the first court name/code");
        if(days==null||days.isEmpty()||days.size()>7||days.stream().anyMatch(d->d==null||d<1||d>7))
            throw new IllegalArgumentException("Choose at least one operating weekday");
        if(opensAt==null||closesAt==null||!opensAt.isBefore(closesAt)||opensAt.getSecond()!=0||closesAt.getSecond()!=0||opensAt.getNano()!=0||closesAt.getNano()!=0||slotMinutes<5||slotMinutes>720||Duration.between(opensAt,closesAt).toMinutes()%slotMinutes!=0)
            throw new IllegalArgumentException("Operating hours must contain whole slots of 5–720 minutes");
        if(pricePerSlot==null||pricePerSlot.signum()<=0||pricePerSlot.stripTrailingZeros().scale()>2||pricePerSlot.compareTo(new BigDecimal("10000000000"))>=0)
            throw new IllegalArgumentException("Supply a positive VND price with at most two decimal places");
    }
    public static UUID resourceId(UUID application,String kind) {
        return UUID.nameUUIDFromBytes((application+":"+kind).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
