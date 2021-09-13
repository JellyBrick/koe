package moe.kyokobot.koe.internal.dto.data;

import com.fasterxml.jackson.annotation.JsonTypeName;
import moe.kyokobot.koe.gateway.Op;
import moe.kyokobot.koe.internal.dto.Codec;
import moe.kyokobot.koe.internal.dto.Data;

@JsonTypeName("" + Op.SELECT_PROTOCOL)
public class SelectProtocol implements Data {
    public String protocol;
    public Codec[] codecs;
    public String rtcConnectionId;
    public UdpInfo data;
    public String address;
    public int port;
    public String mode;

    public SelectProtocol(String protocol,
                          Codec[] codecs,
                          String rtcConnectionId,
                          UdpInfo data) {
        this.protocol = protocol;
        this.codecs = codecs;
        this.rtcConnectionId = rtcConnectionId;
        this.data = data;
        this.address = data.address;
        this.port = data.port;
        this.mode = data.mode;
    }

    public static class UdpInfo {
        public String address;
        public int port;
        public String mode;

        public UdpInfo(String address, int port, String mode) {
            this.address = address;
            this.port = port;
            this.mode = mode;
        }
    }
}
