package com.csg.airtel.aaa4j.domain.constant;

public class AppConstant {
    private AppConstant() {
    }
    public static final long COA_RETRY_INITIAL_BACKOFF_MS = 100;
    public static final long COA_RETRY_MAX_BACKOFF_SECONDS = 2;
    public static final int COA_RETRY_MAX_ATTEMPTS = 2;
    public static final long COA_TIMEOUT_SECONDS = 45;
    public static final String DISCONNECT_ACTION = "Disconnect";


    public static final String CURRENT_BALANCE = "CURRENT_BALANCE";
    public static final String USAGE = "USAGE";
    public static final String UPDATED_AT = "UPDATED_AT";
    public static final String ID = "ID";
    public static final String SERVICE_ID = "SERVICE_ID";

    public static final long GIGAWORD_MULTIPLIER = 4294967296L;
    public static final String DEFAULT_GROUP_ID = "1";
    public static final String BUCKET_INSTANCE_TABLE = "BUCKET_INSTANCE";

    public static final long WINDOW_24_HOURS = 24;
    public static final long WINDOW_12_HOURS = 12;

    public static final int CONSUMPTION_HISTORY_INITIAL_CAPACITY = 24;
    public static final int CONSUMPTION_HISTORY_MAX_SIZE = 90;


}
