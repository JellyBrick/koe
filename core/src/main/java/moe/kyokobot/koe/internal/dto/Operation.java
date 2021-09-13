package moe.kyokobot.koe.internal.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import moe.kyokobot.koe.gateway.Op;
import moe.kyokobot.koe.internal.dto.operation.OperationData;
import moe.kyokobot.koe.internal.dto.operation.OperationHeartbeat;

@JsonTypeInfo(use=JsonTypeInfo.Id.NAME,
        property="op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OperationData.class,
                name = "" + Op.HELLO,
                names = {
                "" + Op.READY,
                "" + Op.SESSION_DESCRIPTION,
                "" + Op.CLIENT_CONNECT,
                "" + Op.CLIENT_DISCONNECT,
                "" + Op.IDENTIFY,
                "" + Op.SELECT_PROTOCOL,
                "" + Op.SPEAKING,
                "" + Op.VIDEO_SINK_WANTS
        }),
        @JsonSubTypes.Type(value = OperationHeartbeat.class, name = "" + Op.HEARTBEAT)
})
public abstract class Operation {
    public int op;
}
