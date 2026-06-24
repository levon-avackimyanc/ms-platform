package com.logistics.planner.api;

import com.logistics.planner.exception.CapacityViolationException;
import com.logistics.planner.exception.LocationCodeConflictException;
import com.logistics.planner.exception.LocationNotFoundException;
import com.logistics.planner.exception.OrderNotFoundException;
import com.logistics.planner.exception.PlanNotFoundException;
import com.logistics.planner.exception.PlanNotInDraftException;
import com.logistics.planner.exception.RouteNotFoundException;
import com.logistics.planner.exception.ShiftViolationException;
import com.logistics.planner.exception.TimeWindowViolationException;
import com.logistics.planner.exception.VehicleNotFoundException;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({
        LocationNotFoundException.class,
        OrderNotFoundException.class,
        VehicleNotFoundException.class,
        PlanNotFoundException.class,
        RouteNotFoundException.class
    })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(RuntimeException ex) {
        return Map.of("error", "NOT_FOUND", "message", ex.getMessage());
    }

    @ExceptionHandler(LocationCodeConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(RuntimeException ex) {
        return Map.of("error", "CONFLICT", "message", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors =
                ex.getBindingResult().getFieldErrors().stream()
                        .collect(
                                Collectors.toMap(
                                        FieldError::getField,
                                        fe ->
                                                fe.getDefaultMessage() != null
                                                        ? fe.getDefaultMessage()
                                                        : "invalid value",
                                        (a, b) -> a));
        return Map.of("error", "VALIDATION_ERROR", "fields", fieldErrors);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArg(IllegalArgumentException ex) {
        return Map.of("error", "VALIDATION_ERROR", "message", ex.getMessage());
    }

    @ExceptionHandler(PlanNotInDraftException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handlePlanNotInDraft(PlanNotInDraftException ex) {
        return Map.of("error", "PLAN_NOT_DRAFT", "message", ex.getMessage());
    }

    @ExceptionHandler(CapacityViolationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> handleCapacityViolation(CapacityViolationException ex) {
        return Map.of("error", "CAPACITY_VIOLATION", "message", ex.getMessage());
    }

    @ExceptionHandler(TimeWindowViolationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> handleTimeWindowViolation(TimeWindowViolationException ex) {
        return Map.of("error", "TIME_WINDOW_VIOLATION", "message", ex.getMessage());
    }

    @ExceptionHandler(ShiftViolationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> handleShiftViolation(ShiftViolationException ex) {
        return Map.of("error", "SHIFT_VIOLATION", "message", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleGeneric(Exception ex) {
        return Map.of("error", "INTERNAL_ERROR", "message", ex.getMessage());
    }
}
