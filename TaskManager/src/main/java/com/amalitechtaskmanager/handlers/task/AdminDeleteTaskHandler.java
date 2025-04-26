package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.exception.FailedToDeleteException;
import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.utils.AuthorizerUtil;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;

import java.util.Map;
import java.util.logging.Logger;


import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.CheckUserRoleUtil.isUserInAdminGroup;

@Slf4j
public class AdminDeleteTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String TABLE_NAME = System.getenv("TASKS_TABLE");
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {

        String idToken = requestEvent.getHeaders().get("Authorization");

        if (idToken == null) {
            return createResponse(401, "Unauthorized-Missing Header");
        }

       if (!AuthorizerUtil.authorize(idToken)){
           return createResponse(403, "not authorized to perform this operation");
       }


        String taskId= requestEvent.getPathParameters().get("taskId");

        if( taskId ==null || taskId.trim().isEmpty()) {

            return  createResponse(400,"Task ID is required");
        }

         try{
             Map<String, AttributeValue> key=Map.of(
                     "taskId",AttributeValue.builder().s(taskId).build()
             );

             DynamoDbFactory.getClient().deleteItem(DeleteItemRequest.builder().tableName(TABLE_NAME).key(key).build());

              return  createResponse(200,"Item Deleted successfully");
         } catch (Exception e) {

             Logger.getAnonymousLogger().info(e.getMessage());
             return createResponse(500,"InternalServerError");
         }



    }
}
