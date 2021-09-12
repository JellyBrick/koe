package moe.kyokobot.koe.gateway;

import moe.kyokobot.koe.VoiceServerInfo;
import moe.kyokobot.koe.codec.Codec;
import moe.kyokobot.koe.codec.DefaultCodecs;
import moe.kyokobot.koe.crypto.EncryptionMode;
import moe.kyokobot.koe.internal.MediaConnectionImpl;
import moe.kyokobot.koe.internal.handler.DiscordUDPConnection;
import moe.kyokobot.koe.internal.json.JsonArray;
import moe.kyokobot.koe.internal.json.JsonObject;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MediaGatewayV5Connection extends AbstractMediaGatewayConnection {
    private static final Logger logger = LoggerFactory.getLogger(MediaGatewayV5Connection.class);

    private int ssrc;
    private SocketAddress address;
    private List<String> encryptionModes;
    private UUID rtcConnectionId;
    private ScheduledFuture<?> heartbeatFuture;

    public MediaGatewayV5Connection(MediaConnectionImpl connection, VoiceServerInfo voiceServerInfo) {
        super(connection, voiceServerInfo, 5);
    }

    @Override
    protected void identify() {
        logger.debug("Identifying...");

        JsonArray streams = new JsonArray();
        streams.add(new JsonObject()
                .add("type", "video")
                .add("rid", "100")
                .add("quality", 100));

        sendInternalPayload(Op.IDENTIFY, new JsonObject()
                .addAsString("server_id", connection.getGuildId())
                .addAsString("user_id", connection.getClient().getClientId())
                .add("streams", streams)
                .add("session_id", voiceServerInfo.getSessionId())
                .add("token", voiceServerInfo.getToken())
                .add("video", true));
    }

    @Override
    protected void handlePayload(JsonObject object) {
        int op = object.getInt("op");

        switch (op) {
            case Op.HELLO: {
                JsonObject data = object.getObject("d");
                int interval = data.getInt("heartbeat_interval");

                logger.debug("Received HELLO, heartbeat interval: {}", interval);
                setupHeartbeats(interval);
                break;
            }
            case Op.READY: {
                JsonObject data = object.getObject("d");
                int port = data.getInt("port");
                String ip = data.getString("ip");
                ssrc = data.getInt("ssrc");
                encryptionModes = data.getArray("modes")
                        .stream()
                        .map(o -> (String) o)
                        .collect(Collectors.toList());
                address = new InetSocketAddress(ip, port);

                connection.getDispatcher().gatewayReady((InetSocketAddress) address, ssrc);
                logger.debug("Voice READY, ssrc: {}", ssrc);
                selectProtocol("udp");
                break;
            }
            case Op.SESSION_DESCRIPTION: {
                JsonObject data = object.getObject("d");
                logger.debug("Got session description: {}", data);

                if (connection.getConnectionHandler() == null) {
                    logger.warn("Received session description before protocol selection? (connection id = {})",
                            this.rtcConnectionId);
                    break;
                }

                connection.getDispatcher().sessionDescription(data);
                connection.getConnectionHandler().handleSessionDescription(data);
                break;
            }
            case Op.CLIENT_CONNECT: {
                JsonObject data = object.getObject("d");
                String user = data.getString("user_id");
                int audioSsrc = data.getInt("audio_ssrc", 0);
                int videoSsrc = data.getInt("video_ssrc", 0);
                int rtxSsrc = data.getInt("rtx_ssrc", 0);
                connection.getDispatcher().userConnected(user, audioSsrc, videoSsrc, rtxSsrc);
                break;
            }
            case Op.CLIENT_DISCONNECT: {
                JsonObject data = object.getObject("d");
                String user = data.getString("user_id");
                connection.getDispatcher().userDisconnected(user);
                break;
            }
            case Op.VIDEO_SINK_WANTS: {
                // Sent only if `video` flag was true while identifying. At time of writing this comment Discord forces
                // it to false on bots (so.. user bot time? /s) due to voice server bug that broke clients or something.
                // After receiving this opcode client can send op 12 with ssrcs for video (audio + 1)
                // and retransmission (audio + 2, not required but results in graphical issues if user joins a VC
                // or even resizes the window) and start sending video data according to received quality hint -
                // so if (d.any < 100) in this payload, the client should send video data with lowered resolution
                // and bitrate.

                sendClientConnect(true, true);
                break;
            }
            default:
                break;
        }
    }

    @Override
    protected void onClose(int code, @Nullable String reason, boolean remote) {
        super.onClose(code, reason, remote);
        if (this.heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
        }
    }

    @Override
    public void updateSpeaking(int mask) {
        sendInternalPayload(Op.SPEAKING, new JsonObject()
                .add("speaking", mask)
                .add("delay", 0)
                .add("ssrc", ssrc));
    }

    @Override
    public int getAudioSSRC() {
        return ssrc;
    }

    @Override
    public int getVideoSSRC() {
        return ssrc + 1;
    }

    @Override
    public int getRetransmissionSSRC() {
        return ssrc + 2;
    }

    private void setupHeartbeats(int interval) {
        if (eventExecutor != null) {
            heartbeatFuture = eventExecutor.scheduleAtFixedRate(this::heartbeat, interval, interval,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void heartbeat() {
        sendInternalPayload(Op.HEARTBEAT, System.currentTimeMillis());
    }

    private void sendClientConnect(boolean enableAudio, boolean enableVideo) {
        JsonArray arr = new JsonArray();
        arr.add(new JsonObject()
                .add("type", "video")
                .add("rid", "100")
                .add("ssrc", ssrc + 1)
                .add("rtx_ssrc", ssrc + 2)
                .add("active", true)
                .add("quality", 100)
                .add("max_bitrate", 2500000)
                .add("max_framerate", 30)
                .add("max_resolution", new JsonObject()
                        .add("type", "fixed")
                        .add("width", 1280)
                        .add("height", 720)));

        sendInternalPayload(Op.CLIENT_CONNECT, new JsonObject()
                .add("audio_ssrc", enableAudio ? ssrc : 0)
                .add("video_ssrc", enableVideo ? (ssrc + 1) : 0)
                .add("rtx_ssrc", enableVideo ? (ssrc + 2) : 0)
                .add("streams", arr));
    }

    private void selectProtocol(String protocol) {
        String mode = EncryptionMode.select(encryptionModes);
        logger.debug("Selected preferred encryption mode: {}", mode);

        rtcConnectionId = UUID.randomUUID();
        logger.debug("Generated new connection id: {}", rtcConnectionId);

        // known values: ["udp", "webrtc"]
        if (protocol.equals("udp")) {
            DiscordUDPConnection conn = new DiscordUDPConnection(connection, address, ssrc);
            conn.connect().thenAccept(ourAddress -> {
                logger.debug("Connected, our external address is: {}", ourAddress);
                connection.getDispatcher().externalIPDiscovered(ourAddress);

                JsonObject udpInfo = new JsonObject()
                        .add("address", ourAddress.getAddress().getHostAddress())
                        .add("port", ourAddress.getPort())
                        .add("mode", mode);

                JsonArray codecs = new JsonArray();
                Stream.concat(DefaultCodecs.audioCodecs.values().stream(), DefaultCodecs.videoCodecs.values().stream())
                        .map(Codec::getJsonDescription)
                        .forEach(codecs::add);

                sendInternalPayload(Op.SELECT_PROTOCOL, new JsonObject()
                        .add("protocol", "udp")
                        .add("codecs", codecs)
                        .add("rtc_connection_id", rtcConnectionId.toString())
                        .add("data", udpInfo)
                        .combine(udpInfo));

                this.updateSpeaking(0);

                sendClientConnect(true, false);
            });

            connection.setConnectionHandler(conn);
            logger.debug("Waiting for session description...");
        } else if (protocol.equals("webrtc")) {
            // do ICE and then generate SDP with info like above?
            throw new IllegalArgumentException("WebRTC protocol is not supported yet!");
        }
    }
}
