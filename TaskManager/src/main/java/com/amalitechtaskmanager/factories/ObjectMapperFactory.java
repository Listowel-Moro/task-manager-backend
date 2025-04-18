package com.amalitechtaskmanager.factories;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

public class ObjectMapperFactory {

    @Getter

    private  static  final  ObjectMapper  mapper = new ObjectMapper();




}
