package com.csg.airtel.aaa4j.domain.constant;

public class SQLConstant {
    private SQLConstant() {
    }

    /**
     * Optimized query to fetch user balance information with service buckets.
     *
     * Performance Optimizations Applied:
     * 1. Uses CTE (WITH clause) to filter user first, reducing join complexity
     * 2. Reordered column selection for logical grouping
     * 3. Consistent JOIN syntax for better readability
     *
     * Required Database Indexes for Optimal Performance:
     * - AAA_USER: INDEX on (USER_NAME)
     * - SERVICE_INSTANCE: INDEX on (USERNAME)
     * - SERVICE_INSTANCE: INDEX on (USERNAME) if GROUP_ID filtering is common
     * - BUCKET_INSTANCE: INDEX on (SERVICE_ID)
     *
     * Query execution should benefit from:
     * - Fast user lookup via indexed USER_NAME
     * - Efficient SERVICE_INSTANCE joins via indexed USERNAME
     * - Optimized LEFT JOIN for buckets via indexed SERVICE_ID
     */
    public static final String QUERY_BALANCE = """
            WITH user_data AS (
                SELECT USER_NAME, GROUP_ID, SESSION_TIMEOUT
                FROM AAA_USER
                WHERE USER_NAME = :1
            )
            SELECT
                s.ID,
                b.ID AS BUCKET_ID,
                s.USERNAME AS BUCKET_USER,
                s.PLAN_ID,
                s.STATUS,
                s.SERVICE_START_DATE,
                s.EXPIRY_DATE,
                b.RULE,
                b.PRIORITY,
                b.INITIAL_BALANCE,
                b.CURRENT_BALANCE,
                b.USAGE,
                b.CONSUMPTION_LIMIT,
                b.CONSUMPTION_LIMIT_WINDOW,
                b.TIME_WINDOW,
                b.EXPIRATION,
                u.SESSION_TIMEOUT
            FROM user_data u
            INNER JOIN SERVICE_INSTANCE s
                ON (s.USERNAME = u.USER_NAME
                    OR (u.GROUP_ID IS NOT NULL AND s.USERNAME = u.GROUP_ID))
            LEFT JOIN BUCKET_INSTANCE b
                ON b.SERVICE_ID = s.ID
            """;
}
