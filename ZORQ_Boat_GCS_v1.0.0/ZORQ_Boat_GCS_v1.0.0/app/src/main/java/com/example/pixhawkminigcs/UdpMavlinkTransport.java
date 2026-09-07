package com.example.pixhawkminigcs;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapts UDP datagrams to InputStream/OutputStream so Dronefleet MavlinkConnection can use them.
 */
public final class UdpMavlinkTransport implements AutoCloseable {
    private static final int BUFFER_SIZE = 65535;

    private final DatagramSocket socket;
    private final InetSocketAddress configuredRemote;
    private volatile InetSocketAddress learnedRemote;
    private final PipedInputStream input;
    private final PipedOutputStream pipeWriter;
    private final ExecutorService receiver;
    private final OutputStream output;

    public UdpMavlinkTransport(int localPort, String remoteHost, int remotePort) throws IOException {
        this.socket = new DatagramSocket(null);
        this.socket.setReuseAddress(true);
        try {
            this.socket.bind(new InetSocketAddress(localPort));
        } catch (SocketException bindError) {
            this.socket.close();
            throw new IOException("UDP port " + localPort + " is already in use. Close Mission Planner/QGroundControl/other GCS apps.", bindError);
        }

        this.configuredRemote = new InetSocketAddress(InetAddress.getByName(remoteHost), remotePort);
        this.input = new PipedInputStream(BUFFER_SIZE);
        this.pipeWriter = new PipedOutputStream(input);
        this.receiver = Executors.newSingleThreadExecutor();

        this.output = new OutputStream() {
            private final byte[] buffer = new byte[BUFFER_SIZE];
            private int position = 0;

            @Override
            public synchronized void write(int b) throws IOException {
                if (position >= buffer.length) flush();
                buffer[position++] = (byte) b;
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                if (len > buffer.length) throw new IOException("MAVLink packet too large");
                if (position + len > buffer.length) flush();
                System.arraycopy(b, off, buffer, position, len);
                position += len;
            }

            @Override
            public synchronized void flush() throws IOException {
                if (position == 0) return;
                InetSocketAddress target = learnedRemote != null ? learnedRemote : configuredRemote;
                DatagramPacket packet = new DatagramPacket(buffer, position, target);
                socket.send(packet);
                position = 0;
            }
        };

        receiver.execute(() -> {
            byte[] receiveBuffer = new byte[BUFFER_SIZE];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(packet);
                    learnedRemote = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    pipeWriter.write(packet.getData(), packet.getOffset(), packet.getLength());
                    pipeWriter.flush();
                } catch (IOException e) {
                    if (!socket.isClosed()) e.printStackTrace();
                    break;
                }
            }
        });
    }

    public PipedInputStream getInputStream() { return input; }
    public OutputStream getOutputStream() { return output; }
    public int getLocalPort() { return socket.getLocalPort(); }

    @Override
    public void close() {
        socket.close();
        receiver.shutdownNow();
        try { pipeWriter.close(); } catch (IOException ignored) {}
        try { input.close(); } catch (IOException ignored) {}
    }
}
