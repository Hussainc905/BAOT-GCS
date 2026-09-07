package com.example.pixhawkminigcs;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.common.*;
import io.dronefleet.mavlink.minimal.*;

public final class MavlinkClient implements AutoCloseable {
    public static final class MissionPoint {
        public int command = 16;
        public double lat;
        public double lon;
        public float alt;

        public MissionPoint(int command, double lat, double lon, float alt) {
            this.command = command;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
        }
    }

    public interface Listener {
        void onLog(String s);
        void onHeartbeat(int s, int c, long m, boolean a, String v);
        void onTelemetry(Double alt, Double speed, Integer sat, Double hdop, Double volts, Integer pct, String ekf);
        void onHeading(double headingDeg);
        void onPosition(double lat, double lon, double relativeAlt, double headingDeg);
        void onStatusText(String text, boolean error);
        void onMissionProgress(String text);
        void onMissionDownloaded(List<MissionPoint> points);
        void onDisconnected(String r);
    }

    private static final int GCS_SYSTEM_ID = 255;
    private static final int GCS_COMPONENT_ID = 190;
    private static final long SENSOR_3D_MAG = 0x00000004L;

    private final Listener listener;
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Long> statusDedupe = new HashMap<>();

    private volatile UdpMavlinkTransport transport;
    private volatile MavlinkConnection connection;
    private volatile int targetSystem = 0;
    private volatile int targetComponent = 0;
    private volatile boolean running = false;
    private volatile int sessionId = 0;
    private volatile ScheduledFuture<?> heartbeatTask;

    private volatile List<MissionPoint> uploadMission = Collections.emptyList();
    private final List<MissionPoint> downloadMission = new ArrayList<>();
    private volatile boolean downloadRequested = false;
    private volatile int downloadExpected = -1;
    private volatile boolean compassBad = false;
    private volatile long lastGlobalPositionAt = 0L;
    private volatile long lastTelemetryRequestAt = 0L;

    public MavlinkClient(Listener listener) {
        this.listener = listener;
    }

    public synchronized void connect(String host, int port) throws IOException {
        closeTransportOnly();
        final int mySession = ++sessionId;
        transport = new UdpMavlinkTransport(port, host, port);
        connection = MavlinkConnection.create(transport.getInputStream(), transport.getOutputStream());
        final MavlinkConnection myConnection = connection;
        running = true;
        listener.onLog("UDP " + host + ":" + port + " • local " + transport.getLocalPort() + " • boat telemetry");
        sendGcsHeartbeat();
        requestBoatTelemetrySafe("initial");
        heartbeatExecutor.schedule(() -> {
            if (running && mySession == sessionId) requestBoatTelemetrySafe("bootstrap-1");
        }, 1200, TimeUnit.MILLISECONDS);
        heartbeatExecutor.schedule(() -> {
            if (running && mySession == sessionId) requestBoatTelemetrySafe("bootstrap-2");
        }, 3500, TimeUnit.MILLISECONDS);
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (running && mySession == sessionId) {
                try {
                    sendGcsHeartbeat();
                } catch (Exception ignored) {
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
        readExecutor.execute(() -> readLoop(mySession, myConnection));
    }

    private void readLoop(int mySession, MavlinkConnection myConnection) {
        try {
            while (running && mySession == sessionId && connection == myConnection) {
                MavlinkMessage<?> message = myConnection.next();
                if (message == null) continue;
                if (!running || mySession != sessionId) break;
                try {
                    processMessage(message);
                } catch (Exception parseError) {
                    Object payload = message.getPayload();
                    String name = payload == null ? "unknown" : payload.getClass().getSimpleName();
                    listener.onLog("Parse warning (" + name + "): " + safeMessage(parseError));
                }
            }
        } catch (Exception e) {
            if (running && mySession == sessionId) {
                listener.onDisconnected(e.getClass().getSimpleName() + ": " + safeMessage(e));
            }
        }
    }

    private void processMessage(MavlinkMessage<?> message) throws Exception {
        Object payload = message.getPayload();
        if (payload == null) return;
        String name = payload.getClass().getSimpleName();

        if (payload instanceof Heartbeat) {
            Heartbeat h = (Heartbeat) payload;
            targetSystem = message.getOriginSystemId();
            targetComponent = message.getOriginComponentId();
            listener.onHeartbeat(
                    targetSystem,
                    targetComponent,
                    h.customMode(),
                    (h.baseMode().value() & 128) != 0,
                    String.valueOf(h.type()));
            long now = System.currentTimeMillis();
            if (now - lastTelemetryRequestAt > 5000L) requestBoatTelemetrySafe("heartbeat");
            return;
        }

        if (name.equals("GlobalPositionInt")) {
            double relativeAlt = num(payload, "relativeAlt") / 1000.0;
            double vx = num(payload, "vx") / 100.0;
            double vy = num(payload, "vy") / 100.0;
            double latitude = num(payload, "lat") / 1e7;
            double longitude = num(payload, "lon") / 1e7;
            double rawHeading = num(payload, "hdg");
            double headingDeg = rawHeading >= 65535.0 ? Double.NaN : rawHeading / 100.0;
            lastGlobalPositionAt = System.currentTimeMillis();
            listener.onTelemetry(relativeAlt, Math.sqrt(vx * vx + vy * vy), null, null, null, null, null);
            if (!Double.isNaN(headingDeg)) listener.onHeading(headingDeg);
            if (Math.abs(latitude) <= 90.0 && Math.abs(longitude) <= 180.0
                    && !(Math.abs(latitude) < 0.0000001 && Math.abs(longitude) < 0.0000001)) {
                listener.onPosition(latitude, longitude, relativeAlt, headingDeg);
            }
            return;
        }

        if (name.equals("GpsRawInt")) {
            int sat = (int) num(payload, "satellitesVisible");
            double eph = num(payload, "eph") / 100.0;
            listener.onTelemetry(null, null, sat, eph, null, null, null);

            // ArduRover installations sometimes stream GPS_RAW_INT before GLOBAL_POSITION_INT.
            // Use it as a map fallback so the boat position is still visible.
            if (System.currentTimeMillis() - lastGlobalPositionAt > 1500L) {
                double latitude = num(payload, "lat") / 1e7;
                double longitude = num(payload, "lon") / 1e7;
                double gpsAlt = num(payload, "alt") / 1000.0;
                double rawCog = num(payload, "cog");
                double cog = rawCog >= 65535.0 ? Double.NaN : rawCog / 100.0;
                if (!Double.isNaN(cog)) listener.onHeading(cog);
                if (Math.abs(latitude) <= 90.0 && Math.abs(longitude) <= 180.0
                        && !(Math.abs(latitude) < 0.0000001 && Math.abs(longitude) < 0.0000001)) {
                    listener.onPosition(latitude, longitude, gpsAlt, cog);
                }
            }
            return;
        }

        if (name.equals("VfrHud")) {
            double groundSpeed = num(payload, "groundspeed");
            double h = num(payload, "heading");
            listener.onTelemetry(null, groundSpeed, null, null, null, null, null);
            if (!Double.isNaN(h)) listener.onHeading(normalizeHeading(h));
            return;
        }

        if (name.equals("Attitude")) {
            double yawRad = num(payload, "yaw");
            if (!Double.isNaN(yawRad)) listener.onHeading(normalizeHeading(Math.toDegrees(yawRad)));
            return;
        }

        if (name.equals("SysStatus")) {
            double volts = num(payload, "voltageBattery") / 1000.0;
            int pct = (int) num(payload, "batteryRemaining");
            listener.onTelemetry(null, null, null, null, volts, pct, null);
            checkCompassHealth(payload);
            return;
        }

        if (name.equals("EkfStatusReport")) {
            double vel = num(payload, "velocityVariance");
            double pos = num(payload, "posHorizVariance");
            double comp = num(payload, "compassVariance");
            String state = (vel < 1 && pos < 1 && comp < 1) ? "HEALTHY" : "CHECK";
            listener.onTelemetry(null, null, null, null, null, null, state);
            return;
        }

        if (name.equals("Statustext")) {
            handleStatusText(payload);
            return;
        }

        if (name.equals("MissionRequestInt") || name.equals("MissionRequest")) {
            int seq = (int) num(payload, "seq");
            sendMissionItem(seq);
            return;
        }

        if (name.equals("MissionCount")) {
            handleMissionCount(payload);
            return;
        }

        if (name.equals("MissionItemInt")) {
            handleMissionItem(payload, true);
            return;
        }

        if (name.equals("MissionItem")) {
            handleMissionItem(payload, false);
            return;
        }

        if (name.equals("MissionAck")) {
            listener.onMissionProgress("Mission ACK: " + String.valueOf(call(payload, "type")));
        }
    }

    private void handleStatusText(Object payload) throws Exception {
        Object textObject = call(payload, "text");
        String text;
        if (textObject instanceof byte[]) {
            text = new String((byte[]) textObject).replace("\u0000", "").trim();
        } else {
            text = String.valueOf(textObject).replace("\u0000", "").trim();
        }
        int severity = 6;
        try {
            severity = (int) numObj(call(payload, "severity"));
        } catch (Exception ignored) {
        }

        String lower = text.toLowerCase(Locale.US);
        boolean error = severity <= 4
                || lower.contains("prearm")
                || lower.contains("failsafe")
                || lower.contains("fail")
                || lower.contains("error")
                || lower.contains("variance")
                || lower.contains("unhealthy")
                || lower.contains("not healthy")
                || lower.contains("bad compass")
                || lower.contains("compass health");

        emitStatus(text, error);
    }

    private void checkCompassHealth(Object sysStatus) {
        try {
            long present = (long) num(sysStatus, "onboardControlSensorsPresent");
            long enabled = (long) num(sysStatus, "onboardControlSensorsEnabled");
            long healthy = (long) num(sysStatus, "onboardControlSensorsHealth");

            boolean hasCompass = (present & SENSOR_3D_MAG) != 0;
            boolean compassEnabled = (enabled & SENSOR_3D_MAG) != 0;
            boolean compassHealthy = (healthy & SENSOR_3D_MAG) != 0;
            boolean badNow = hasCompass && compassEnabled && !compassHealthy;

            if (badNow && !compassBad) {
                compassBad = true;
                emitStatus("Bad Compass Health", true);
            } else if (!badNow && compassBad) {
                compassBad = false;
                emitStatus("Compass Health OK", false);
            }
        } catch (Exception ignored) {
            // Some MAVLink dialect/library versions expose SYS_STATUS bitmasks differently.
            // STATUSTEXT detection remains active even if this fallback cannot be read.
        }
    }

    private synchronized void emitStatus(String text, boolean error) {
        if (text == null || text.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        Long last = statusDedupe.get(text);
        if (last != null && now - last < 2500) return;
        statusDedupe.put(text, now);
        listener.onStatusText(text, error);
    }

    private double num(Object object, String method) throws Exception {
        return numObj(call(object, method));
    }

    private double numObj(Object x) throws Exception {
        if (x instanceof Number) return ((Number) x).doubleValue();
        try {
            return ((Number) call(x, "value")).doubleValue();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private Object call(Object object, String method) throws Exception {
        return object.getClass().getMethod(method).invoke(object);
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private synchronized void sendGcsHeartbeat() throws IOException {
        if (connection == null) return;
        connection.send2(
                GCS_SYSTEM_ID,
                GCS_COMPONENT_ID,
                Heartbeat.builder()
                        .type(MavType.MAV_TYPE_GCS)
                        .autopilot(MavAutopilot.MAV_AUTOPILOT_INVALID)
                        .baseMode(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
                        .customMode(0)
                        .systemStatus(MavState.MAV_STATE_ACTIVE)
                        .mavlinkVersion(3)
                        .build());
    }

    private void requestBoatTelemetrySafe(String source) {
        try {
            requestBoatTelemetry();
            listener.onLog("Telemetry request sent (" + source + ")");
        } catch (Exception e) {
            listener.onLog("Telemetry request warning: " + safeMessage(e));
        }
    }

    /**
     * Explicitly asks ArduRover for the messages used by the boat dashboard.
     * This is important on TELEM ports whose SRx stream-rate parameters are low or zero.
     */
    private synchronized void requestBoatTelemetry() throws Exception {
        if (!running || connection == null) return;
        lastTelemetryRequestAt = System.currentTimeMillis();

        setMessageInterval(0,  1000000);  // HEARTBEAT, 1 Hz
        setMessageInterval(1,   500000);  // SYS_STATUS, 2 Hz
        setMessageInterval(24,  500000);  // GPS_RAW_INT, 2 Hz
        setMessageInterval(30,  200000);  // ATTITUDE, 5 Hz
        setMessageInterval(33,  200000);  // GLOBAL_POSITION_INT, 5 Hz
        setMessageInterval(42, 1000000);  // MISSION_CURRENT, 1 Hz
        setMessageInterval(74,  200000);  // VFR_HUD, 5 Hz
        setMessageInterval(193, 500000);  // EKF_STATUS_REPORT, 2 Hz

        // Older ArduRover / telemetry configurations may still depend on REQUEST_DATA_STREAM.
        try {
            Object builder = Class.forName("io.dronefleet.mavlink.common.RequestDataStream")
                    .getMethod("builder").invoke(null);
            set(builder, "targetSystem", targetSystem);
            set(builder, "targetComponent", targetComponent);
            set(builder, "reqStreamId", enumConst("io.dronefleet.mavlink.common.MavDataStream", "MAV_DATA_STREAM_ALL"));
            set(builder, "reqMessageRate", 4);
            set(builder, "startStop", 1);
            sendReflect(call(builder, "build"));
        } catch (Exception legacy) {
            listener.onLog("Legacy stream request skipped: " + safeMessage(legacy));
        }
    }

    private void setMessageInterval(int messageId, int intervalUs) throws IOException {
        sendCommand(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, messageId, intervalUs, 0, 0, 0, 0, 0);
    }

    private double normalizeHeading(double degrees) {
        double h = degrees % 360.0;
        return h < 0 ? h + 360.0 : h;
    }

    public synchronized void arm(boolean arm) throws IOException {
        sendCommand(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, arm ? 1 : 0, 0, 0, 0, 0, 0, 0);
        listener.onLog(arm ? "ARM command sent" : "DISARM command sent");
    }

    public synchronized void setRoverMode(int mode) throws IOException {
        sendCommand(MavCmd.MAV_CMD_DO_SET_MODE, 1, mode, 0, 0, 0, 0, 0);
        listener.onLog("Rover mode command: " + mode);
    }

    public synchronized void startMission() throws IOException {
        sendCommand(MavCmd.MAV_CMD_MISSION_START, 0, 0, 0, 0, 0, 0, 0);
        listener.onLog("MISSION START sent");
    }

    private void sendCommand(MavCmd command, float p1, float p2, float p3, float p4, float p5, float p6, float p7) throws IOException {
        ensure();
        CommandLong q = CommandLong.builder()
                .targetSystem(targetSystem)
                .targetComponent(targetComponent)
                .command(command)
                .confirmation(0)
                .param1(p1)
                .param2(p2)
                .param3(p3)
                .param4(p4)
                .param5(p5)
                .param6(p6)
                .param7(p7)
                .build();
        connection.send2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, q);
    }

    // WRITE mission to Pixhawk
    public synchronized void uploadMission(List<MissionPoint> points) throws Exception {
        ensure();
        downloadRequested = false;
        uploadMission = new ArrayList<>(points);

        Object builder = Class.forName("io.dronefleet.mavlink.common.MissionCount")
                .getMethod("builder")
                .invoke(null);
        set(builder, "targetSystem", targetSystem);
        set(builder, "targetComponent", targetComponent);
        set(builder, "count", points.size());
        trySetMissionType(builder);
        sendReflect(call(builder, "build"));
        listener.onMissionProgress("WRITE: sent mission count " + points.size() + "; waiting for Pixhawk requests");
    }

    private synchronized void sendMissionItem(int seq) throws Exception {
        if (seq < 0 || seq >= uploadMission.size()) return;
        MissionPoint p = uploadMission.get(seq);

        Object builder = Class.forName("io.dronefleet.mavlink.common.MissionItemInt")
                .getMethod("builder")
                .invoke(null);
        set(builder, "targetSystem", targetSystem);
        set(builder, "targetComponent", targetComponent);
        set(builder, "seq", seq);
        set(builder, "frame", enumConst("io.dronefleet.mavlink.common.MavFrame", "MAV_FRAME_GLOBAL_RELATIVE_ALT_INT"));
        set(builder, "command", enumByValue("io.dronefleet.mavlink.common.MavCmd", p.command));
        set(builder, "current", seq == 0 ? 1 : 0);
        set(builder, "autocontinue", 1);
        for (int i = 1; i <= 4; i++) set(builder, "param" + i, 0f);
        set(builder, "x", (int) Math.round(p.lat * 1e7));
        set(builder, "y", (int) Math.round(p.lon * 1e7));
        set(builder, "z", p.alt);
        trySetMissionType(builder);
        sendReflect(call(builder, "build"));
        listener.onMissionProgress("WRITE: waypoint " + (seq + 1) + " / " + uploadMission.size());
    }

    // READ mission from Pixhawk
    public synchronized void downloadMission() throws Exception {
        ensure();
        uploadMission = Collections.emptyList();
        downloadMission.clear();
        downloadExpected = -1;
        downloadRequested = true;

        Object builder = Class.forName("io.dronefleet.mavlink.common.MissionRequestList")
                .getMethod("builder")
                .invoke(null);
        set(builder, "targetSystem", targetSystem);
        set(builder, "targetComponent", targetComponent);
        trySetMissionType(builder);
        sendReflect(call(builder, "build"));
        listener.onMissionProgress("READ: requesting mission list from Pixhawk...");
    }

    private synchronized void handleMissionCount(Object payload) throws Exception {
        if (!downloadRequested) return;
        downloadExpected = (int) num(payload, "count");
        downloadMission.clear();
        listener.onMissionProgress("READ: Pixhawk reports " + downloadExpected + " mission items");

        if (downloadExpected <= 0) {
            sendMissionAckAccepted();
            downloadRequested = false;
            listener.onMissionDownloaded(new ArrayList<>());
            return;
        }
        requestMissionItem(0);
    }

    private synchronized void requestMissionItem(int seq) throws Exception {
        Object builder = Class.forName("io.dronefleet.mavlink.common.MissionRequestInt")
                .getMethod("builder")
                .invoke(null);
        set(builder, "targetSystem", targetSystem);
        set(builder, "targetComponent", targetComponent);
        set(builder, "seq", seq);
        trySetMissionType(builder);
        sendReflect(call(builder, "build"));
        listener.onMissionProgress("READ: requesting item " + (seq + 1) + " / " + downloadExpected);
    }

    private synchronized void handleMissionItem(Object payload, boolean integerCoordinates) throws Exception {
        if (!downloadRequested || downloadExpected < 0) return;

        int seq = (int) num(payload, "seq");
        int command = (int) numObj(call(payload, "command"));
        double lat = num(payload, "x");
        double lon = num(payload, "y");
        float alt = (float) num(payload, "z");
        if (integerCoordinates) {
            lat /= 1e7;
            lon /= 1e7;
        }

        while (downloadMission.size() <= seq) downloadMission.add(null);
        downloadMission.set(seq, new MissionPoint(command, lat, lon, alt));

        int next = firstMissing(downloadMission, downloadExpected);
        if (next >= 0) {
            requestMissionItem(next);
        } else {
            sendMissionAckAccepted();
            downloadRequested = false;
            ArrayList<MissionPoint> completed = new ArrayList<>();
            for (int i = 0; i < downloadExpected; i++) completed.add(downloadMission.get(i));
            listener.onMissionProgress("READ complete: " + completed.size() + " items received");
            listener.onMissionDownloaded(completed);
        }
    }

    private int firstMissing(List<MissionPoint> list, int expected) {
        for (int i = 0; i < expected; i++) {
            if (i >= list.size() || list.get(i) == null) return i;
        }
        return -1;
    }

    private synchronized void sendMissionAckAccepted() {
        try {
            Object builder = Class.forName("io.dronefleet.mavlink.common.MissionAck")
                    .getMethod("builder")
                    .invoke(null);
            set(builder, "targetSystem", targetSystem);
            set(builder, "targetComponent", targetComponent);
            set(builder, "type", enumConst("io.dronefleet.mavlink.common.MavMissionResult", "MAV_MISSION_ACCEPTED"));
            trySetMissionType(builder);
            sendReflect(call(builder, "build"));
        } catch (Exception e) {
            listener.onLog("Mission ACK warning: " + safeMessage(e));
        }
    }

    private void trySetMissionType(Object builder) {
        try {
            set(builder, "missionType", enumConst(
                    "io.dronefleet.mavlink.common.MavMissionType",
                    "MAV_MISSION_TYPE_MISSION"));
        } catch (Exception ignored) {
        }
    }

    private Object enumConst(String className, String name) throws Exception {
        return Enum.valueOf((Class<Enum>) Class.forName(className), name);
    }

    private Object enumByValue(String className, int value) throws Exception {
        for (Object e : Class.forName(className).getEnumConstants()) {
            if ((int) numObj(call(e, "value")) == value) return e;
        }
        return enumConst(className, "MAV_CMD_NAV_WAYPOINT");
    }

    private void set(Object builder, String name, Object value) throws Exception {
        for (Method method : builder.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                Class<?> type = method.getParameterTypes()[0];
                Object converted = value;
                if (type == int.class && value instanceof Number) converted = ((Number) value).intValue();
                else if (type == long.class && value instanceof Number) converted = ((Number) value).longValue();
                else if (type == float.class && value instanceof Number) converted = ((Number) value).floatValue();
                else if (type == double.class && value instanceof Number) converted = ((Number) value).doubleValue();
                method.invoke(builder, converted);
                return;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private void sendReflect(Object payload) throws Exception {
        for (Method method : connection.getClass().getMethods()) {
            if (method.getName().equals("send2") && method.getParameterCount() == 3) {
                method.invoke(connection, GCS_SYSTEM_ID, GCS_COMPONENT_ID, payload);
                return;
            }
        }
        throw new IOException("MAVLink send2 unavailable");
    }

    private void ensure() throws IOException {
        if (!running || connection == null) throw new IOException("Not connected");
    }

    public synchronized void disconnect() {
        boolean hadConnection = running || transport != null || connection != null;
        closeTransportOnly();
        if (hadConnection) listener.onDisconnected("Disconnected by user");
    }

    private synchronized void closeTransportOnly() {
        running = false;
        sessionId++;
        downloadRequested = false;
        downloadExpected = -1;
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        if (transport != null) {
            transport.close();
            transport = null;
        }
        connection = null;
    }

    @Override
    public synchronized void close() {
        closeTransportOnly();
        readExecutor.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }
}
