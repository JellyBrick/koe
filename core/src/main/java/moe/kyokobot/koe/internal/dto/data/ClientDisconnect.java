package moe.kyokobot.koe.internal.dto.data;

import com.fasterxml.jackson.annotation.JsonTypeName;
import moe.kyokobot.koe.gateway.Op;
import moe.kyokobot.koe.internal.dto.Data;

@JsonTypeName("" + Op.CLIENT_DISCONNECT)
public class ClientDisconnect implements Data {
    public String userId;
}
