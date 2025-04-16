package taskmanager.handlers.task;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetTask implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String TABLE_NAME = "Tasks";
    private static final ObjectMapper mapper = new ObjectMapper();
    private final DynamoDbClient dbClient = DynamoDbClient.create();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String taskId = request.getPathParameters().get("taskId");

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("taskId", AttributeValue.fromS(taskId));

        try {
            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(key)
                    .build();

            GetItemResponse response = dbClient.getItem(getItemRequest);
            if (!response.hasItem()) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(404)
                        .withBody("{\"error\": \"Task not found\"}");
            }

            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<String, AttributeValue> entry : response.item().entrySet()) {
                result.put(entry.getKey(), attributeValueToSimpleValue(entry.getValue()));
            }

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(mapper.writeValueAsString(result));

        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private Object attributeValueToSimpleValue(AttributeValue value) {
        if (value.s() != null) return value.s();
        if (value.n() != null) return value.n();
        if (value.bool() != null) return value.bool();
        if (value.hasSs()) return value.ss();
        if (value.hasNs()) return value.ns();
        if (value.hasBs()) return value.bs();
        if (value.m() != null) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, AttributeValue> entry : value.m().entrySet()) {
                map.put(entry.getKey(), attributeValueToSimpleValue(entry.getValue()));
            }
            return map;
        }
        if (value.l() != null) {
            List<Object> list = new java.util.ArrayList<>();
            for (AttributeValue av : value.l()) {
                list.add(attributeValueToSimpleValue(av));
            }
            return list;
        }
        return null;
    }
}
