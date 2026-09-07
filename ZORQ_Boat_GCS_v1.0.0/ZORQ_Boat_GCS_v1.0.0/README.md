# زورق — Boat GCS v1.0.0

Dedicated Android ground-control application for **ArduRover Boat** on Pixhawk/CUAV flight controllers.

## This build is boat-only

- App label: **زورق**
- Separate Android application ID: `com.example.zorqboatgcs` so it can be installed beside the aircraft 101 GCS app.
- Accepts MAVLink vehicle types `MAV_TYPE_SURFACE_BOAT` and `MAV_TYPE_GROUND_ROVER`.
- Rover modes: MANUAL, STEERING, HOLD, LOITER, AUTO, RTL plus ARM/DISARM and MISSION START.
- Dashboard: heading, ground speed, satellites, HDOP, EKF, battery, Pixhawk link quality and Rover controller state.
- Map, live boat position, track, offline satellite cache, Messages, mission read/write and waypoints are retained from v0.3.5.

## CUAV V5 / ArduRover telemetry compatibility changes

This version actively asks ArduRover for the telemetry messages used by the dashboard after connection and after the first heartbeat. It requests `SYS_STATUS`, `GPS_RAW_INT`, `ATTITUDE`, `GLOBAL_POSITION_INT`, `VFR_HUD`, `MISSION_CURRENT`, and `EKF_STATUS_REPORT` using `MAV_CMD_SET_MESSAGE_INTERVAL`, with a legacy `REQUEST_DATA_STREAM` fallback.

`GPS_RAW_INT` is also used as a map-position fallback if `GLOBAL_POSITION_INT` is not arriving, and `VFR_HUD`/`ATTITUDE` can provide speed/heading fallback data.

UDP port 14550 is now bound strictly. If another GCS already owns the port, زورق shows an error instead of silently using a random port and appearing connected without telemetry.

If no MAVLink heartbeat is received after about 5 seconds, the dashboard displays **NO MAVLINK DATA / CHECK TELEM / PW-LINK**. In that case verify the CUAV V5 TELEM port is configured for MAVLink and that its baud rate matches the PW-Link serial baud.

## Access passwords retained

- First activation: `223232`
- Annual password: `22523232`

## GitHub build

Upload the **contents** of this folder to the repository. The included `.github/workflows/build.yml` locates `gradlew` automatically and uploads the debug APK as an artifact named `ZORQ-Boat-GCS-v1.0.0-debug-apk`.
