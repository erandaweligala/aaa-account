package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.cdr.*;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import lombok.Getter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

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
    @Getter
    public static class AccountingMetrics {
        // Getters
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
        User cdrUser = buildUserCdr(request,session.getGroupId());
        Network cdrNetwork = buildNetworkCdr(request);
        Accounting cdrAccounting = buildAccountingCdr(metrics, session.getServiceId(), session.getPreviousUsageBucketId());

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
    public static User buildUserCdr(AccountingRequestDto request,String groupId) {
        return User.builder()
                .userName(request.username())
                .groupId(groupId)
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
    public static Accounting buildAccountingCdr(AccountingMetrics metrics, String serviceId, String bucketId) {
        long totalUsage = calculateTotalUsage(metrics.getInputOctets(), metrics.getOutputOctets(),
                metrics.getInputGigawords(), metrics.getOutputGigawords());
        return Accounting.builder()
                .acctStatusType(metrics.getAcctStatusType())
                .acctSessionTime(metrics.getSessionTime() != null ? metrics.getSessionTime() : 0)
                .acctInputOctets(metrics.getInputOctets())
                .acctOutputOctets(metrics.getOutputOctets())
                .acctInputPackets(0)
                .acctOutputPackets(0)
                .totalUsage(totalUsage)
                .acctInputGigawords(metrics.getInputGigawords() != null ? metrics.getInputGigawords() : 0)
                .acctOutputGigawords(metrics.getOutputGigawords() != null ? metrics.getOutputGigawords() : 0)
                .serviceId(serviceId)
                .bucketId(bucketId)
                .build();
    }

    /**
     * Builds a complete AccountingCDREvent for COA Disconnect events
     *
     * @param session The session being disconnected
     * @param username The username associated with the session
     * @return COA Disconnect CDR event
     */
    public static AccountingCDREvent buildCoaDisconnectCDREvent(Session session, String username) {
        log.infof("Building COA Disconnect CDR event for session: %s, user: %s", session.getSessionId(), username);

        // Build session CDR
        SessionCdr cdrSession = SessionCdr.builder()
                .sessionId(session.getSessionId())
                .nasIdentifier(null)
                .nasIpAddress(session.getNasIp())
                .nasPort(session.getNasPortId())
                .nasPortType(session.getNasPortId() )
                .sessionTime(String.valueOf(session.getSessionTime() != null ? session.getSessionTime() : 0))
                .startTime(session.getSessionInitiatedTime() != null
                    ? session.getSessionInitiatedTime().atZone(java.time.ZoneId.systemDefault()).toInstant()
                    : Instant.now())
                .updateTime(Instant.now())
                .build();

        // Build user CDR
        User cdrUser = User.builder()
                .userName(username)
                .groupId(session.getGroupId())
                .build();

        // Build network CDR
        Network cdrNetwork = Network.builder()
                .framedIpAddress(session.getFramedId())
                .framedProtocol(null)
                .serviceType(null)
                .calledStationId(null)
                .build();

        // Build COA section
        COA coa = COA.builder()
                .coaType("Disconnect-Request")
                .coaCode(40)
                .destinationPort(3799)
                .build();

        // Build RADIUS attributes
        List<RadiusAttribute> attributes = new ArrayList<>();

        attributes.add(RadiusAttribute.builder()
                .type(1)
                .name("User-Name")
                .value(username)
                .build());

        if (session.getNasIp() != null) {
            attributes.add(RadiusAttribute.builder()
                    .type(4)
                    .name("NAS-IP-Address")
                    .value(session.getNasIp())
                    .build());
        }

        if (session.getFramedId() != null) {
            attributes.add(RadiusAttribute.builder()
                    .type(8)
                    .name("Framed-IP-Address")
                    .value(session.getFramedId())
                    .build());
        }

        attributes.add(RadiusAttribute.builder()
                .type(44)
                .name("Acct-Session-Id")
                .value(session.getSessionId())
                .build());

        Radius radius = Radius.builder()
                .attributes(attributes)
                .build();

        // Build payload
        Payload payload = Payload.builder()
                .session(cdrSession)
                .user(cdrUser)
                .network(cdrNetwork)
                .coa(coa)
                .radius(radius)
                .build();

        // Build and return the complete CDR event
        return AccountingCDREvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventTypes.COA_DISCONNECT_REQUEST.name())
                .eventVersion("1.0")
                .eventTimestamp(Instant.now())
                .source("radius-server")
                .partitionKey(session.getSessionId())
                .payload(payload)
                .build();
    }

    /**
     * Generates and sends a CDR event asynchronously.
     * This method consolidates the duplicate CDR generation logic from StartHandler, InterimHandler, and StopHandler.
     *
     * @param request The accounting request
     * @param session The session data
     * @param accountProducer The producer to send the CDR event
     * @param cdrBuilder Function that builds the appropriate CDR event type
     */
    public static void generateAndSendCDR(
            AccountingRequestDto request,
            Session session,
            AccountProducer accountProducer,
            BiFunction<AccountingRequestDto, Session, AccountingCDREvent> cdrBuilder) {
        try {
            AccountingCDREvent cdrEvent = cdrBuilder.apply(request, session);

            // Run asynchronously without blocking
            accountProducer.produceAccountingCDREvent(cdrEvent)
                    .subscribe()
                    .with(
                            success -> log.infof("CDR event sent successfully for session: %s", request.sessionId()),
                            failure -> log.errorf(failure, "Failed to send CDR event for session: %s", request.sessionId())
                    );
        } catch (Exception e) {
            log.errorf(e, "Error building CDR event for session: %s", request.sessionId());
        }
    }

    private static long calculateTotalUsage(Long inputOctets, Long outputOctets,

                                            Integer inputGigawords, Integer outputGigawords) {

        long gigawordMultiplier = 4294967296L; // 2^32

        long totalGigawords = (long) (inputGigawords != null ? inputGigawords : 0) +

                (long) (outputGigawords != null ? outputGigawords : 0);

        long totalOctets = (inputOctets != null ? inputOctets : 0L) +

                (outputOctets != null ? outputOctets : 0L);

        return (totalGigawords * gigawordMultiplier) + totalOctets;

    }
}