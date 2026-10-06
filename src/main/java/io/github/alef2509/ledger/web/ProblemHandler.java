package io.github.alef2509.ledger.web;

import io.github.alef2509.ledger.application.WrongExpectedVersionException;
import io.github.alef2509.ledger.domain.WalletRuleViolation;
import io.github.alef2509.ledger.eventstore.ConcurrencyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

@RestControllerAdvice
class ProblemHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(WalletRuleViolation.class)
    ProblemDetail ruleViolation(WalletRuleViolation e) {
        HttpStatus status = e.code().equals("not-found") ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setType(URI.create("urn:problem-type:ledger:" + e.code()));
        return problem;
    }

    @ExceptionHandler(WrongExpectedVersionException.class)
    ProblemDetail wrongVersion(WrongExpectedVersionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, e.getMessage());
    }

    @ExceptionHandler(ConcurrencyException.class)
    ProblemDetail concurrency(ConcurrencyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
