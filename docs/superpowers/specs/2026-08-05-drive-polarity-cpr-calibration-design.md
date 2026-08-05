# Drive Polarity and Encoder CPR Calibration Design

## Problem

A `geometry_msgs/Twist` command with `angular.z = +0.25 rad/s` made the
robot rotate clockwise. ROS defines positive yaw as counter-clockwise. During
the same test, `/odom` repeatedly reported approximately `-15 rad/s` instead
of a value near `+0.25 rad/s`.

The commanded differential wheel speeds were `left = -0.025 m/s` and
`right = +0.025 m/s`, while the reported odometry implied approximately
`left = +1.54 m/s` and `right = -1.46 m/s`. Both feedback signs were therefore
opposite the targets. This causes the wheel PID loops to apply more output
instead of reducing the error.

The firmware uses `ESP32Encoder.attachFullQuad()`. FIT0403 is specified as
1440 pulses per gearbox-output revolution, but full quadrature counts all four
edges, so the software count scale is 5760 counts per gearbox-output
revolution. Using 1440 makes measured speed and odometry four times too large.

## Selected Approach

Correct the motor-driver output polarity in firmware and keep encoder signs
unchanged. This preserves the observed agreement between physical clockwise
motion and negative odometry while making positive wheel targets produce
physical forward wheel motion.

Use explicit per-wheel output polarity constants rather than silently swapping
channel arguments. Both constants start at `-1.0` for the measured hardware.
The constants are applied only to the final PID duty sent to `applyPwm()`;
targets, measured encoder speeds, serial feedback, and ROS odometry retain ROS
sign conventions.

Set encoder counts per revolution to 5760 in every firmware and host location:

- ESP32 PID speed conversion
- ROS base-driver parameter default
- `DriveConfig` default
- deployed `potner_params.yaml`
- documentation and consistency tests

No safety, Nav2, exploration, wheel geometry, or speed-limit behavior changes
are included.

## Data Flow After Correction

For `angular.z > 0` and `linear.x = 0`:

1. The ROS base driver produces a negative left-wheel target and positive
   right-wheel target.
2. The PID computes signed control effort in the same convention.
3. Firmware output-polarity constants convert that effort to the installed
   BTS7960 wiring polarity.
4. The physical left wheel turns backward and the physical right wheel turns
   forward.
5. Encoder counts decrease on the left and increase on the right.
6. `/odom` reports positive angular velocity and increasing yaw.

## Validation

Automated tests must fail before implementation when the default/configured CPR
is still 1440 or when the firmware does not expose and apply negative output
polarity for both wheels. Existing kinematics, protocol, safety, and exploration
tests must continue to pass.

Hardware validation is performed with the drive wheels off the ground:

- Command `angular.z = +0.25 rad/s` for at most 15 seconds.
- Verify left wheel backward and right wheel forward.
- Verify `/odom angular.z` is positive and of the same order as `0.25 rad/s`,
  not a value near 15 rad/s.
- Verify `/odom linear.x` remains near zero.
- Stop immediately if either wheel accelerates instead of settling.

## Deployment

The ESP32 firmware must be rebuilt and flashed because the polarity and PID
count conversion live on the microcontroller. The ROS workspace must then be
rebuilt and sourced because the host odometry configuration also changes.
Changing only one side is invalid: firmware and Jetson must both use 5760.

No Git commit will be created, as requested.
