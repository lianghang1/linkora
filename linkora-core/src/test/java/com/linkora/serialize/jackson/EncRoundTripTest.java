package com.linkora.serialize.jackson;

import com.linkora.core.message.LinkoraMessage;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/** enc 标记信封往返（0.6.0 传输加密的协议层验证）。 */
public class EncRoundTripTest {

    @Test
    public void encSurvivesWireRoundTrip() {
        MessageJsonCodec codec = new MessageJsonCodec();
        byte[] payload = "QlD7yoZVeuIyBF0r1bOLPMJ/V07JnitXOYodgIUm4f/nni+1Sa".getBytes(StandardCharsets.UTF_8);
        LinkoraMessage sent = new LinkoraMessage.Builder()
                .messageId("m-enc-1").clientId("c1").messageType("tomb-sync")
                .payload(payload).createdAt(1L).schemaVersion(1)
                .enc("A256GCM:k1").build();
        String wire = codec.encodeMessage(sent);
        System.out.println("[wire] " + wire);
        LinkoraMessage received = codec.decodeMessage(wire);
        assertEquals("enc 标记必须在线路往返中保留", "A256GCM:k1", received.getEnc());
    }
}
