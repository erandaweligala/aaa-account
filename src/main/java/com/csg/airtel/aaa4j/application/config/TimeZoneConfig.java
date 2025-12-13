package com.csg.airtel.aaa4j.application.config;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.TimeZone;
@ApplicationScoped
public class TimeZoneConfig {
    @PostConstruct
    void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"));
    }
}
