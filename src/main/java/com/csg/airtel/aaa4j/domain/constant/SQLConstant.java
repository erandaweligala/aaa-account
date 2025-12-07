package com.csg.airtel.aaa4j.domain.constant;

public class SQLConstant {
    private SQLConstant() {
    }
    public static final String QUERY_BALANCE = """
                        SELECT
                           s.ID ,
                           b.RULE,
                           b.PRIORITY,
                           b.INITIAL_BALANCE,
                           b.CURRENT_BALANCE,
                           b.USAGE,
                           s.EXPIRY_DATE,
                           s.SERVICE_START_DATE,
                           s.PLAN_ID,
                           b.ID AS BUCKET_ID,
                           s.STATUS,
                           s.USERNAME  AS BUCKET_USER,
                           b.CONSUMPTION_LIMIT,
                           u.SESSION_TIMEOUT,
                           b.TIME_WINDOW,
                           b.CONSUMPTION_LIMIT_WINDOW,
                           b.EXPIRATION,
                           b.IS_UNLIMITED,
                           s.IS_GROUP,
                           u.CONCURRENCY
                        FROM SERVICE_INSTANCE s
                        JOIN AAA_USER  u
                          ON s.USERNAME  = u.USER_NAME
                          OR (u.group_id IS NOT NULL AND s.USERNAME = u.group_id)
                        LEFT JOIN BUCKET_INSTANCE b
                          ON s.ID  = b.service_id
                        WHERE u.USER_NAME = :1
            """;
}
