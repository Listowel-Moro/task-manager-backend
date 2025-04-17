package com.amalitechtaskmanager.utils;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class AttributeValueConverter {

    public  static  Optional<Object> attributeValueToSimpleValue(AttributeValue value) {
        if (value == null) return Optional.empty();

        if (value.s() != null) return Optional.of(value.s());
        if (value.n() != null) return Optional.of(value.n());
        if (value.bool() != null) return Optional.of(value.bool());
        if (value.hasSs()) return Optional.of(value.ss());
        if (value.hasNs()) return Optional.of(value.ns());
        if (value.hasBs()) return Optional.of(value.bs());

        if (value.m() != null && !value.m().isEmpty()) {
            Map<String, Object> map = value.m().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> attributeValueToSimpleValue(e.getValue()).orElse("blank")
                    ));
            return Optional.of(map);
        }

        if (value.l() != null && !value.l().isEmpty()) {
            List<Object> list = value.l().stream()
                    .map(v -> attributeValueToSimpleValue(v).orElse(null))
                    .collect(Collectors.toList());
            return Optional.of(list);
        }

        return Optional.empty();
    }


}
