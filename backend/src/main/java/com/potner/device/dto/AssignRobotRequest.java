package com.potner.device.dto;

import jakarta.validation.constraints.NotBlank;

/** 식물에 담당 로봇을 배정하는 요청이다. */
public record AssignRobotRequest(@NotBlank String robotId) {
}
