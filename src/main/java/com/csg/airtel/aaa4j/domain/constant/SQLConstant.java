package com.csg.airtel.aaa4j.domain.constant;

public class SQLConstant {
    private SQLConstant() {
    }
    public static final String QUERY_BALANCE = """
            SELECT
               s.ID, b.RULE, b.PRIORITY, b.INITIAL_BALANCE, b.CURRENT_BALANCE,
               b.USAGE, s.EXPIRY_DATE, s.SERVICE_START_DATE, s.PLAN_ID,
               b.ID AS BUCKET_ID, s.STATUS, s.USERNAME AS BUCKET_USER,
               b.CONSUMPTION_LIMIT, u.SESSION_TIMEOUT, b.TIME_WINDOW,
               b.CONSUMPTION_LIMIT_WINDOW, b.EXPIRATION, b.IS_UNLIMITED,
               s.IS_GROUP, u.CONCURRENCY, u.TEMPLATE_ID
            FROM AAA_USER u
            JOIN SERVICE_INSTANCE s ON s.USERNAME = u.USER_NAME
            JOIN BUCKET_INSTANCE b ON s.ID = b.service_id
            WHERE u.USER_NAME = :1
            UNION ALL
            SELECT
               s.ID, b.RULE, b.PRIORITY, b.INITIAL_BALANCE, b.CURRENT_BALANCE,
               b.USAGE, s.EXPIRY_DATE, s.SERVICE_START_DATE, s.PLAN_ID,
               b.ID AS BUCKET_ID, s.STATUS, s.USERNAME AS BUCKET_USER,
               b.CONSUMPTION_LIMIT, u.SESSION_TIMEOUT, b.TIME_WINDOW,
               b.CONSUMPTION_LIMIT_WINDOW, b.EXPIRATION, b.IS_UNLIMITED,
               s.IS_GROUP, u.CONCURRENCY, u.TEMPLATE_ID
            FROM AAA_USER u
            JOIN SERVICE_INSTANCE s ON s.USERNAME = u.group_id
            JOIN BUCKET_INSTANCE b ON s.ID = b.service_id
            WHERE u.USER_NAME = :1
            AND u.group_id IS NOT NULL
            AND u.group_id <> u.USER_NAME
            """;

    public static final String QUERY_MESSAGE_TEMPLATES = """
    SELECT
        ID,
        MESSAGE_CONTENT,
        MESSAGE_TYPE,
        QUOTA_PERCENTAGE,
        SUPER_TEMPLATE_ID
    FROM CHILD_TEMPLATE_TABLE
    """;
}
