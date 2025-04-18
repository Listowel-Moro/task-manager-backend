package com.amalitechtaskmanager.factories;

import lombok.Getter;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class DynamoDbFactory  {

    @Getter
    private  static  final DynamoDbClient client = DynamoDbClient.create();

}
