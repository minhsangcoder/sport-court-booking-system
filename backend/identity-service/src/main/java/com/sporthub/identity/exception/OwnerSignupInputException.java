package com.sporthub.identity.exception;
import com.sporthub.common.dto.FieldError;
import org.springframework.http.HttpStatus;
import java.util.List;
public class OwnerSignupInputException extends IdentityException {
 private final List<FieldError> fields;
 public OwnerSignupInputException(String message,List<FieldError> fields){super(HttpStatus.BAD_REQUEST,"IDENTITY-OWNER-SIGNUP",message);this.fields=List.copyOf(fields);}
 public List<FieldError> fields(){return fields;}
}
