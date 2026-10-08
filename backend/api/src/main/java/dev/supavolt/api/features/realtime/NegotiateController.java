package dev.supavolt.api.features.realtime;

import dev.supavolt.api.common.Tokens;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The SignalR negotiate step. Clients POST here first; the answer says WebSockets is the only
 * transport, and the client then opens {@code /realtime?id=...&access_token=...}. A plain
 * {@code @Controller}, so it stays outside the /api prefix next to the hub.
 */
@Controller
@Hidden
public class NegotiateController {

    @PostMapping("/realtime/negotiate")
    @ResponseBody
    public Map<String, Object> negotiate(@RequestParam(defaultValue = "0") int negotiateVersion) {
        var connectionId = Tokens.randomHex(32);
        var body = new LinkedHashMap<String, Object>();

        if (negotiateVersion >= 1) {
            body.put("negotiateVersion", 1);
            body.put("connectionToken", Tokens.randomHex(32));
        }
        body.put("connectionId", connectionId);
        body.put("availableTransports", List.of(Map.of("transport", "WebSockets", "transferFormats", List.of("Text"))));
        return body;
    }
}
