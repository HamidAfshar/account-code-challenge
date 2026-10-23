package com.afshar.codechallenge.api;

import com.afshar.codechallenge.domain.exception.AccountAlreadyExistException;
import com.afshar.codechallenge.domain.exception.AccountNotFoundException;
import com.afshar.codechallenge.domain.exception.InsufficientFundsException;
import com.afshar.codechallenge.domain.exception.TransactionDuplicationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail accountNotFound(AccountNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({AccountAlreadyExistException.class,
            TransactionDuplicationException.class,
            InsufficientFundsException.class})
    public ProblemDetail conflict(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail validationFailure(ConstraintViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidOperation(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ArithmeticException.class)
    public ProblemDetail balanceOverflow(ArithmeticException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Resulting balance exceeds the supported range");
    }

}
