package moe.kyokobot.koe.internal.dto.data;

import com.fasterxml.jackson.annotation.JsonTypeName;
import moe.kyokobot.koe.gateway.Op;
import moe.kyokobot.koe.internal.dto.Data;

@JsonTypeName("" + Op.SPEAKING)
public class Speaking implements Data {
    public Speaking(int speaking, int delay, int ssrc) {
        this.speaking = speaking;
        this.delay = delay;
        this.ssrc = ssrc;
    }
    public int speaking;
    public int delay;
    public int ssrc;
}
