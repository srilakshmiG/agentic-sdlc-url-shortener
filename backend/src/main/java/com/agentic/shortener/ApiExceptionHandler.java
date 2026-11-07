package com.agentic.shortener;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One RFC 9457 error shape for the whole API. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UrlValidator.InvalidUrlException.class)
    ProblemDetail invalidUrl(UrlValidator.InvalidUrlException e) { return problem(HttpStatus.BAD_REQUEST, e.getMessage()); }

    @ExceptionHandler(LinkService.BadRequestException.class)
    ProblemDetail badRequest(LinkService.BadRequestException e) { return problem(HttpStatus.BAD_REQUEST, e.getMessage()); }

    @ExceptionHandler(LinkService.NotFoundException.class)
    ProblemDetail notFound(LinkService.NotFoundException e) { return problem(HttpStatus.NOT_FOUND, e.getMessage()); }

    @ExceptionHandler(LinkService.ConflictException.class)
    ProblemDetail conflict(LinkService.ConflictException e) { return problem(HttpStatus.CONFLICT, e.getMessage()); }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    ProblemDetail missing(java.util.NoSuchElementException e) { return problem(HttpStatus.NOT_FOUND, e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegal(IllegalArgumentException e) { return problem(HttpStatus.BAD_REQUEST, e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail state(IllegalStateException e) { return problem(HttpStatus.CONFLICT, e.getMessage()); }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
