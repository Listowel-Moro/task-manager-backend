package com.amalitechtaskmanager.factories;

import lombok.Getter;
import software.amazon.awssdk.services.sns.SnsClient;

public class SNSFactory  {

    @Getter
    private static final SnsClient snsClient = SnsClient.create();

}
