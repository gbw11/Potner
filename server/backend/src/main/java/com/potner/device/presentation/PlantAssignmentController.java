package com.potner.device.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.device.application.DeviceRegistrationService;
import com.potner.device.dto.AssignRobotRequest;
import com.potner.device.dto.PlantAssignmentResponse;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plants/{plantId}/assignment")
@Tag(name = "로봇 배정", description = "식물과 담당 로봇의 배정 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PlantAssignmentController {

    private final DeviceRegistrationService deviceRegistrationService;

    public PlantAssignmentController(DeviceRegistrationService deviceRegistrationService) {
        this.deviceRegistrationService = deviceRegistrationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "식물에 담당 로봇 배정",
            description = """
                    배정된 뒤부터 그 로봇이 보내는 측정값이 이 식물로 저장된다.

                    활성 배정은 식물당 하나, 로봇당 하나다. 이미 배정된 식물이면 409
                    (PLANT_ALREADY_ASSIGNED), 이미 다른 식물을 담당하는 로봇이면 409
                    (ROBOT_ALREADY_ASSIGNED) 다. 바꾸려면 먼저 해제해야 한다.

                    타인 식물이나 타인 로봇은 존재 여부를 노출하지 않도록 404 다.
                    """
    )
    public PlantAssignmentResponse assignRobot(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @Valid @RequestBody AssignRobotRequest request
    ) {
        return deviceRegistrationService.assignRobot(
                principal.userId(),
                plantId,
                request.robotId()
        );
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "식물의 로봇 배정 해제",
            description = """
                    행을 지우지 않고 해제 시각만 남기므로 배정 이력이 보존된다.
                    해제 후에는 같은 식물에 다른 로봇을 배정할 수 있다.

                    해제 시점부터 그 로봇의 측정값은 저장되지 않는다.
                    배정된 로봇이 없으면 404 (PLANT_ASSIGNMENT_NOT_FOUND) 다.
                    """
    )
    public void unassignRobot(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        deviceRegistrationService.unassignRobot(principal.userId(), plantId);
    }
}
