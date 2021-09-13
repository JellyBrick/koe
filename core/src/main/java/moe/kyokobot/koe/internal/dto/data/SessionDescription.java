package moe.kyokobot.koe.internal.dto.data;

import com.fasterxml.jackson.annotation.JsonTypeName;
import moe.kyokobot.koe.gateway.Op;
import moe.kyokobot.koe.internal.dto.Data;

@JsonTypeName("" + Op.SESSION_DESCRIPTION)
public class SessionDescription implements Data {
    public String mode;
    public String audioCodec;
    public int[] secretKey;
}
