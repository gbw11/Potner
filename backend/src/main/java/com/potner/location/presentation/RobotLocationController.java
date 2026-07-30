package com.potner.location.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.location.application.RobotLocationService;
import com.potner.location.domain.RobotLocationType;
import com.potner.location.dto.RegisterRobotLocationRequest;
import com.potner.location.dto.RobotLocationListResponse;
import com.potner.location.dto.RobotLocationResponse;
import com.potner.location.dto.UpdateLocationPoseRequest;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/robots/{robotId}/locations")
@Tag(name = "로봇 위치", description = "스테이션·대기·햇빛·마중 위치 등록 및 좌표 관리 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class RobotLocationController {

    private final RobotLocationService robotLocationService;

    public RobotLocationController(RobotLocationService robotLocationService) {
        this.robotLocationService = robotLocationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "위치 등록",
            description = """
                    type 은 WATER_STATION(급수 스테이션) / HOME(대기 장소) / SUNLIGHT(햇빛 자리) /
                    GREETING(마중 지점)이다. 로봇 하나에 종류별로 하나만 등록할 수 있다(중복 409).

                    stationCode 는 WATER_STATION 에만 넣는다. 물리 장치가 있는 위치가 그것뿐이라
                    다른 종류에 넣으면 400, 빼면 400 이다. 코드는 전역 UNIQUE 라 이미 등록된
                    코드는 409 다.

                    좌표는 여기서 받지 않는다. 좌표는 지도를 만든 설치자가 RViz 에서 읽어
                    PUT .../locations/{type}/pose 로 넣는다. 등록 직후 poseConfigured 는 false 이고,
                    좌표가 없는 위치로는 로봇을 보낼 수 없다.
                    """
    )
    public RobotLocationResponse register(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String robotId,
            @Valid @RequestBody RegisterRobotLocationRequest request
    ) {
        return robotLocationService.register(principal.userId(), robotId, request);
    }

    @GetMapping
    @Operation(
            summary = "위치 목록 조회",
            description = "로봇에 등록된 위치 전부다. poseConfigured 가 false 면 좌표 입력 전이다."
    )
    public RobotLocationListResponse getLocations(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String robotId
    ) {
        return robotLocationService.getLocations(principal.userId(), robotId);
    }

    @PutMapping("/{type}/pose")
    @Operation(
            summary = "위치 좌표 입력",
            description = """
                    SLAM 지도(map 프레임)의 좌표다. x·y 는 m, yaw 는 rad(-pi ~ pi)이며 셋 다
                    필수다 — 지도를 다시 그리면 세 값이 모두 무효가 되므로 부분 수정이 없다.
                    RViz 에서 해당 지점에 커서를 올리면 좌표가 보인다.

                    지도를 다시 만들었으면 모든 위치의 좌표를 다시 넣어야 한다. 원점이 바뀌어
                    이전 좌표는 엉뚱한 곳을 가리킨다.
                    """
    )
    public RobotLocationResponse updatePose(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String robotId,
            @PathVariable RobotLocationType type,
            @Valid @RequestBody UpdateLocationPoseRequest request
    ) {
        return robotLocationService.updatePose(principal.userId(), robotId, type, request);
    }
}
