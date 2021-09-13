package moe.kyokobot.koe.internal.dto.data;

import com.fasterxml.jackson.annotation.JsonTypeName;
import moe.kyokobot.koe.gateway.Op;
import moe.kyokobot.koe.internal.dto.Data;

@JsonTypeName("" + Op.READY)
public class Ready implements Data {
    public int port;
    public String ip;
    public int ssrc;
    public String[] modes;
}
