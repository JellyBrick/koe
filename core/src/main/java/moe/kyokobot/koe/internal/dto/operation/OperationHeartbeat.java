package moe.kyokobot.koe.internal.dto.operation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import moe.kyokobot.koe.gateway.Op;
import moe.kyokobot.koe.internal.dto.Operation;

@JsonTypeName("" + Op.HEARTBEAT)
public class OperationHeartbeat extends Operation {
    @JsonProperty("d")
    public long data = System.currentTimeMillis();

    public OperationHeartbeat() {
        this.op = Op.HEARTBEAT;
    }
}
