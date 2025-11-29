package com.csg.airtel.aaa4j.domain.model.response;


import lombok.Getter;
import lombok.Setter;

import java.time.Instant;


@Getter
@Setter
public class ApiResponse<T> {
    private Instant timestamp;
    private String message;
    private T data;
}
