package spike;

import java.util.Map;

public class Hello {
    public Map<String, Object> handle(Map<String, Object> event) {
        return Map.of("statusCode", 200, "body", "ok");
    }
}
