/// 로봇이 현재 수행 중인 행동 상태입니다.
///
/// 백엔드 API 또는 WebSocket 상태값을 이 enum으로 변환하면 헤더 UI가
/// 별도 변경 없이 현재 상태를 반영할 수 있습니다.
enum RobotStatus { drinkingWater, takingSunlight, takingWind, resting }
