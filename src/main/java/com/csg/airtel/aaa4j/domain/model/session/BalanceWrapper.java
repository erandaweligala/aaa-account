package com.csg.airtel.aaa4j.domain.model.session;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BalanceWrapper {
    private String sessionTimeOut;
    private long concurrency;
    private Balance balance;
}
