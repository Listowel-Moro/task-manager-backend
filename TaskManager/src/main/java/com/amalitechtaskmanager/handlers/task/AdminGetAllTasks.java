package com.amalitechtaskmanager.handlers.task;
import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.utils.AuthorizerUtil;
import com.amalitechtaskmanager.utils.DynamoFilterUtil;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;


import java.util.*;
import java.util.stream.Collectors;

import static com.amalitechtaskmanager.constants.StringConstants.TABLE_NAME;
import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.AttributeValueConverter.attributeValueToSimpleValue;

public class AdminGetAllTasks  implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {



    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {

        String idToken = requestEvent.getHeaders().get("Authorization");

        if (idToken == null) {
            return createResponse(401, "{\"error\": \"Not authorized to perform this operation\"}");
        }

        if (!AuthorizerUtil.authorize(idToken)){
            return createResponse(401, "{\"error\": \"Not authorized to perform this operation\"}");
        }

        Map<String,String> queryParams= requestEvent.getQueryStringParameters();

        ScanRequest scanRequest=  DynamoFilterUtil.buildScanRequestWithFilters(TABLE_NAME,queryParams);
        ScanResponse response= DynamoDbFactory.getClient().scan(scanRequest);


try {
    List<Map<String, Optional<Object>>> result = response.items().stream()
            .map(item -> item.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> attributeValueToSimpleValue(e.getValue())
                    )))
            .toList();

    return createResponse(200, ObjectMapperFactory.getMapper().writeValueAsString(result));

} catch (Exception e) {
    return createResponse(500, "{\"error\": \"" + e.getMessage() + "\"}");
}


}
}
