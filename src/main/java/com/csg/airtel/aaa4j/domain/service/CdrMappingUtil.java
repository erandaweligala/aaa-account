package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.cdr.EventTypes;
import com.csg.airtel.aaa4j.domain.model.cdr.*;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Utility class for mapping AccountingRequestDto and Session data to CDR events.
 * Provides common mapping logic used across all accounting handlers (Start, Interim, Stop).
 */
public class CdrMappingUtil {

    private static final Logger log = Logger.getLogger(CdrMappingUtil.class);

    private CdrMappingUtil() {
        // Private constructor to prevent instantiation
    }

    /**
     * Parameter object to encapsulate accounting metrics data
     */
    public static class AccountingMetrics {
        private final String acctStatusType;
        private final String eventType;
        private final Integer sessionTime;
        private final Long inputOctets;
        private final Long outputOctets;
        private final Integer inputGigawords;
        private final Integer outputGigawords;

        private AccountingMetrics(String acctStatusType, String eventType, Integer sessionTime,
                                  Long inputOctets, Long outputOctets,
                                  Integer inputGigawords, Integer outputGigawords) {
            this.acctStatusType = acctStatusType;
            this.eventType = eventType;
            this.sessionTime = sessionTime;
            this.inputOctets = inputOctets;
            this.outputOctets = outputOctets;
            this.inputGigawords = inputGigawords;
            this.outputGigawords = outputGigawords;
        }

        public static AccountingMetrics forStart() {
            return new AccountingMetrics(
                    "Start",
                    EventTypes.ACCOUNTING_START.name(),
                    0,
                    0L,
                    0L,
                    0,
                    0
            );
        }

        public static AccountingMetrics forInterim(AccountingRequestDto request) {
            return new AccountingMetrics(
                    "Interim-Update",
                    EventTypes.ACCOUNTING_INTERIM.name(),
                    request.sessionTime(),
                    (long) request.inputOctets(),
                    (long) request.outputOctets(),
                    request.inputGigaWords(),
                    request.outputGigaWords()
            );
        }

        public static AccountingMetrics forStop(AccountingRequestDto request) {
            return new AccountingMetrics(
                    "Stop",
                    EventTypes.ACCOUNTING_STOP.name(),
                    request.sessionTime(),
                    (long) request.inputOctets(),
                    (long) request.outputOctets(),
                    request.inputGigaWords(),
                    request.outputGigaWords()
            );
        }

        // Getters
        public String getAcctStatusType() { return acctStatusType; }
        public String getEventType() { return eventType; }
        public Integer getSessionTime() { return sessionTime; }
        public Long getInputOctets() { return inputOctets; }
        public Long getOutputOctets() { return outputOctets; }
        public Integer getInputGigawords() { return inputGigawords; }
        public Integer getOutputGigawords() { return outputGigawords; }
    }

    /**
     * Builds a complete AccountingCDREvent for START events
     */
    public static AccountingCDREvent buildStartCDREvent(AccountingRequestDto request, Session session) {
        return buildCDREvent(request, session, AccountingMetrics.forStart());
    }

    /**
     * Builds a complete AccountingCDREvent for INTERIM-UPDATE events
     */
    public static AccountingCDREvent buildInterimCDREvent(AccountingRequestDto request, Session session) {
        return buildCDREvent(request, session, AccountingMetrics.forInterim(request));
    }

    /**
     * Builds a complete AccountingCDREvent for STOP events
     */
    public static AccountingCDREvent buildStopCDREvent(AccountingRequestDto request, Session session) {
        return buildCDREvent(request, session, AccountingMetrics.forStop(request));
    }

    /**
     * Internal method to build a complete AccountingCDREvent with all components
     */
    private static AccountingCDREvent buildCDREvent(
            AccountingRequestDto request,
            Session session,
            AccountingMetrics metrics) {

        log.infof("starting CDREvent for request: %s", session.getSessionId());

        SessionCdr cdrSession = buildSessionCdr(request, metrics.getSessionTime(), metrics.getEventType());
        User cdrUser = buildUserCdr(request);
        Network cdrNetwork = buildNetworkCdr(request);
        Accounting cdrAccounting = buildAccountingCdr(metrics);

        Payload payload = Payload.builder()
                .session(cdrSession)
                .user(cdrUser)
                .network(cdrNetwork)
                .accounting(cdrAccounting)
                .build();

        return AccountingCDREvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(metrics.getEventType())
                .eventVersion("1.0")
                .eventTimestamp(Instant.now())
                .source("AAA-Service")
                .payload(payload)
                .build();
    }

    /**
     * Builds a Session CDR object from request and session data
     */
    public static SessionCdr buildSessionCdr(
            AccountingRequestDto request,
            Integer sessionTime,
            String eventType) {

        String sessionTimeStr = sessionTime != null ? String.valueOf(sessionTime) : "0";
        Instant eventEndTime = Objects.equals(eventType, "Stop") ? request.timestamp() : null;

        return SessionCdr.builder()
                .sessionId(request.sessionId())
                .sessionTime(sessionTimeStr)
                .startTime(request.timestamp())
                .updateTime(Instant.now())
                .nasIdentifier(request.nasIdentifier())
                .nasIpAddress(request.nasIP())
                .nasPort(request.nasPortId())
                .nasPortType(request.nasPortId())
                .sessionStopTime(eventEndTime)
                .build();
    }

    /**
     * Builds a User CDR object from request data
     */
    public static User buildUserCdr(AccountingRequestDto request) {
        return User.builder()
                .userName(request.username())
                .build();
    }

    /**
     * Builds a Network CDR object from request data
     */
    public static Network buildNetworkCdr(AccountingRequestDto request) {
        return Network.builder()
                .framedIpAddress(request.framedIPAddress())
                .calledStationId(request.nasIP())
                .build();
    }

    /**
     * Builds an Accounting CDR object from metrics
     */
    public static Accounting buildAccountingCdr(AccountingMetrics metrics) {
        return Accounting.builder()
                .acctStatusType(metrics.getAcctStatusType())
                .acctSessionTime(metrics.getSessionTime() != null ? metrics.getSessionTime() : 0)
                .acctInputOctets(metrics.getInputOctets())
                .acctOutputOctets(metrics.getOutputOctets())
                .acctInputPackets(0)
                .acctOutputPackets(0)
                .acctInputGigawords(metrics.getInputGigawords() != null ? metrics.getInputGigawords() : 0)
                .acctOutputGigawords(metrics.getOutputGigawords() != null ? metrics.getOutputGigawords() : 0)
                .build();
    }
}