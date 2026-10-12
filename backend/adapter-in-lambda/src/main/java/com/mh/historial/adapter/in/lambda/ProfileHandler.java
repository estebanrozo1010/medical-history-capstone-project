package com.mh.historial.adapter.in.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Map;

/** Lambda mh-profile (6a: solo confirma que la petición autenticada llega). */
public class ProfileHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  @Override
  public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(501)
        .withHeaders(Map.of("Content-Type", "application/json",
            "Access-Control-Allow-Origin", System.getenv().getOrDefault("ALLOWED_ORIGIN", "")))
        .withBody("{\"code\":\"NO_IMPLEMENTADO\",\"message\":\"mh-profile sin lógica todavía\"}");
  }
}
