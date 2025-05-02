package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.HashMap;
import java.util.Map;

import static com.amalitechtaskmanager.constants.StringConstants.TABLE_NAME;
import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;

public class MemberGetAnalyticsHandler  implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {


        String userId= requestEvent.getPathParameters().get("userId");
        if(userId==null||userId.isEmpty()) {
            return createResponse(requestEvent, 433,"ID require");
        }

        try {


            ScanRequest  scanRequest= ScanRequest.builder()
                    .tableName(TABLE_NAME)
                    .filterExpression("userId = :userId")
                    .expressionAttributeValues(
                            Map.of(":userId", AttributeValue.builder().s(userId).build()
                            )
                    ).build();
            ScanResponse response= DynamoDbFactory.getClient().scan(scanRequest);

            int totalTasks=0;
            int open=0, completed=0,expired=0,closed=0;

            for (Map<String,AttributeValue> item: response.items()) {

                totalTasks++;
                String status = item.getOrDefault("status",AttributeValue.builder()
                        .s("UNKNOWN").build()).s();
                        switch(status){
                         case "OPEN"->open++;
                         case "COMPLETED"->completed++;
                         case "CLOSED"->closed++;
                        }
            }

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("userId", userId);
            resultMap.put("totalTasks", totalTasks);
            resultMap.put("open", open);
            resultMap.put("completed", completed);
            resultMap.put("expired", expired);
            resultMap.put("closed", closed);

            String  result= ObjectMapperFactory.getMapper().writeValueAsString(resultMap);
             return  createResponse(requestEvent, 200,result);


        } catch (Exception e) {
            return  createResponse(requestEvent, 500,"Internal Server Error"+e.getMessage());
        }


    }
}
