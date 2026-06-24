package com.logistics.planner.api.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record PlanInitRequest(@NotNull LocalDate planDate) {}
