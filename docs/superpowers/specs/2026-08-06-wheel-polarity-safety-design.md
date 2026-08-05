# Wheel Polarity and Stop Safety Design

## Evidence

With each wheel turned by hand in the robot-forward direction:

- Left only produced negative linear velocity and positive angular velocity.
- Right only produced positive linear velocity and positive angular velocity.
- In both cases, `abs(angular_z)` was approximately ten times `abs(linear_x)`, which matches a 0.20 m wheel separation with one wheel stationary.

The raw left encoder polarity is therefore reversed while the raw right encoder polarity is correct.

## Design

The ESP32 firmware will normalize encoder counts at the hardware boundary with integer signs `LEFT_ENCODER_SIGN = -1` and `RIGHT_ENCODER_SIGN = +1`. Integer signs avoid losing precision in long-running cumulative encoder counts. Normalized counts will be used both by the wheel PID controllers and in odometry feedback sent to the Jetson, so polarity is corrected exactly once.

Based on the observed physical wheel motion under the current pure-rotation command, normalized PID output will use `LEFT_MOTOR_OUTPUT_SIGN = +1.0f` and `RIGHT_MOTOR_OUTPUT_SIGN = -1.0f`.

An explicit drive-enable gate will prevent the PID loop from writing non-zero PWM after a stop frame, a zero-velocity frame, or command watchdog timeout. While disabled, the firmware will synchronize encoder baselines and report measured speed without producing motor output. A valid non-zero velocity frame is the only operation that re-enables drive.

Velocity fields will be parsed with complete-string `strtof` validation. Frames with missing or extra fields, non-numeric suffixes, `NaN`, or infinity are rejected without changing the drive state or watchdog timestamp.

## Minimum Drive Feed-forward

Hardware testing showed that a 0.03 m/s wheel target only barely moved while 0.08 m/s started both wheels reliably. With the existing gains and a stalled wheel, the PID output reaches approximately 0.16 duty after four seconds, so `MIN_DRIVE_DUTY = 0.16f` is the initial measured stiction compensation.

The minimum duty will be added as a signed feed-forward based on the wheel target, before final output clamping. It is not a permanent PWM floor: PID feedback can subtract from it when the measured speed is above target. A zero wheel target clears its own integral and previous error and returns zero output, including one-wheel-zero arc commands. A direct target direction reversal also clears that wheel's PID history before computing its first output, preventing stored integral from driving briefly in the old direction. Whole-robot stop, zero command, and watchdog paths continue to bypass PID and force both PWM outputs to zero.

## Verification

Source contract tests will check the four polarity constants, ensure normalized counts feed both PID and serial feedback, ensure stop/zero/watchdog paths disable drive, ensure malformed velocity fields cannot reach the only drive-enable assignment, ensure minimum drive duty is signed by target, and ensure zero-target and direction-reversal paths clear residual PID state before output. A PlatformIO build will validate the firmware where the local toolchain is available. Hardware verification will start with wheels lifted, a 0.03 m/s forward command, and then the configured Nav2 maximum 0.35 rad/s positive angular command before autonomous exploration is allowed.

## Constraints

- Do not commit changes.
- Preserve `COUNTS_PER_REV = 5760.0f` and the 0.175 m/s Jetson safety limit.
- Do not modify unrelated SLAM, navigation, or mission behavior.
