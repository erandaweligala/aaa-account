package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.cdr.*;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import io.smallrye.mutiny.Uni;
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
    private static final String M_CDR = "generateCDR";
    public static final String DISCONNECT_REQUEST = "Disconnect-Request";
    public static final String AAA_SERVICE = "AAA-Service";

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
                    Integer.toUnsignedLong(request.inputOctets()),
                    Integer.toUnsignedLong(request.outputOctets()),
                    request.inputGigaWords(),
                    request.outputGigaWords()
            );
        }

        public static AccountingMetrics forStop(AccountingRequestDto request) {
            return new AccountingMetrics(
                    "Stop",
                    EventTypes.ACCOUNTING_STOP.name(),
                    request.sessionTime(),
                    Integer.toUnsignedLong(request.inputOctets()),
                    Integer.toUnsignedLong(request.outputOctets()),
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

        LoggingUtil.logInfo(log, M_CDR, "starting CDREvent for request: %s", session.getSessionId());

        SessionCdr cdrSession = buildSessionCdr(request, metrics.getSessionTime(), metrics.getEventType());
        User cdrUser = buildUserCdr(request,session.getGroupId());
        Network cdrNetwork = buildNetworkCdr(request);
        Accounting cdrAccounting = buildAccountingCdr(metrics, session.getServiceId(), session.getPreviousUsageBucketId(),session.getSessionUsage());

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
                .source(AAA_SERVICE)
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
    public static Accounting buildAccountingCdr(AccountingMetrics metrics, String serviceId, String bucketId,long previousUsage) {
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
                .sessionUsage(previousUsage)
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
        LoggingUtil.logInfo(log, M_CDR, "Building COA Disconnect CDR event for session: %s, user: %s", session.getSessionId(), username);

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
                .coaType(DISCONNECT_REQUEST)
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
     * Builds a CDR event for a CoA Request initiation.
     * Generated when a CoA disconnect request is about to be sent to the NAS.
     *
     * @param session  The session for which CoA request is being initiated
     * @param username The username associated with the session
     * @return CoA Request CDR event
     */
    public static AccountingCDREvent buildCoaRequestCDREvent(Session session, String username) {
        LoggingUtil.logInfo(log, M_CDR, "Building COA Request CDR event for session: %s, user: %s", session.getSessionId(), username);

        SessionCdr cdrSession = buildCoaSessionCdr(session);
        User cdrUser = User.builder()
                .userName(username)
                .groupId(session.getGroupId())
                .build();
        Network cdrNetwork = Network.builder()
                .framedIpAddress(session.getFramedId())
                .calledStationId(session.getNasIp())
                .build();

        COA coa = COA.builder()
                .coaType(DISCONNECT_REQUEST)
                .coaCode(40)
                .destinationPort(3799)
                .build();

        List<RadiusAttribute> attributes = buildCoaRadiusAttributes(session, username);
        Radius radius = Radius.builder().attributes(attributes).build();

        Payload payload = Payload.builder()
                .session(cdrSession)
                .user(cdrUser)
                .network(cdrNetwork)
                .coa(coa)
                .radius(radius)
                .build();

        return AccountingCDREvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventTypes.COA_REQUEST.name())
                .eventVersion("1.0")
                .eventTimestamp(Instant.now())
                .source(AAA_SERVICE)
                .partitionKey(session.getSessionId())
                .payload(payload)
                .build();
    }

    /**
     * Builds a CDR event for a CoA Response received from the NAS.
     * Generated when a CoA disconnect response (ACK or NAK) is received.
     *
     * @param session        The session for which CoA response was received
     * @param username       The username associated with the session
     * @param responseStatus The response status from NAS (e.g. "ACK", "NAK")
     * @return CoA Response CDR event
     */
    public static AccountingCDREvent buildCoaResponseCDREvent(Session session, String username, String responseStatus) {
        LoggingUtil.logInfo(log, M_CDR, "Building COA Response CDR event for session: %s, user: %s, status: %s",
                session.getSessionId(), username, responseStatus);

        SessionCdr cdrSession = buildCoaSessionCdr(session);
        User cdrUser = User.builder()
                .userName(username)
                .groupId(session.getGroupId())
                .build();
        Network cdrNetwork = Network.builder()
                .framedIpAddress(session.getFramedId())
                .calledStationId(session.getNasIp())
                .build();

        COA coa = COA.builder()
                .coaType(DISCONNECT_REQUEST)
                .coaCode(40)
                .destinationPort(3799)
                .coaResponseStatus(responseStatus)
                .build();

        List<RadiusAttribute> attributes = buildCoaRadiusAttributes(session, username);
        Radius radius = Radius.builder().attributes(attributes).build();

        Payload payload = Payload.builder()
                .session(cdrSession)
                .user(cdrUser)
                .network(cdrNetwork)
                .coa(coa)
                .radius(radius)
                .build();

        return AccountingCDREvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventTypes.COA_RESPONSE.name())
                .eventVersion("1.0")
                .eventTimestamp(Instant.now())
                .source(AAA_SERVICE)
                .partitionKey(session.getSessionId())
                .payload(payload)
                .build();
    }

    /**
     * Builds a SessionCdr from a Session object for CoA CDR events.
     */
    private static SessionCdr buildCoaSessionCdr(Session session) {
        return SessionCdr.builder()
                .sessionId(session.getSessionId())
                .nasIpAddress(session.getNasIp())
                .nasPort(session.getNasPortId())
                .nasPortType(session.getNasPortId())
                .sessionTime(String.valueOf(session.getSessionTime() != null ? session.getSessionTime() : 0))
                .startTime(session.getSessionInitiatedTime() != null
                        ? session.getSessionInitiatedTime().atZone(java.time.ZoneId.systemDefault()).toInstant()
                        : Instant.now())
                .updateTime(Instant.now())
                .build();
    }

    /**
     * Builds the common RADIUS attributes list for CoA CDR events.
     */
    private static List<RadiusAttribute> buildCoaRadiusAttributes(Session session, String username) {
        List<RadiusAttribute> attributes = new ArrayList<>();
        attributes.add(RadiusAttribute.builder()
                .type(1).name("User-Name").value(username).build());
        if (session.getNasIp() != null) {
            attributes.add(RadiusAttribute.builder()
                    .type(4).name("NAS-IP-Address").value(session.getNasIp()).build());
        }
        if (session.getFramedId() != null) {
            attributes.add(RadiusAttribute.builder()
                    .type(8).name("Framed-IP-Address").value(session.getFramedId()).build());
        }
        attributes.add(RadiusAttribute.builder()
                .type(44).name("Acct-Session-Id").value(session.getSessionId()).build());
        return attributes;
    }

    /**
     * Generates a CDR event and returns a Uni so callers can chain it into the
     * accounting pipeline. Returning the Uni propagates back-pressure: if the
     * CDR producer slows down, the upstream consumer's concurrency gate (16)
     * throttles polling instead of accumulating orphan in-flight publishes.
     */
    public static Uni<Void> generateAndSendCDR(
            AccountingRequestDto request,
            Session session,
            AccountProducer accountProducer,
            BiFunction<AccountingRequestDto, Session, AccountingCDREvent> cdrBuilder,
            String serviceId,
            String bucketId) {
        AccountingCDREvent cdrEvent;
        try {
            cdrEvent = cdrBuilder.apply(request, session);
            if (cdrEvent.getPayload() != null && cdrEvent.getPayload().getAccounting() != null) {
                cdrEvent.getPayload().getAccounting().setServiceId(serviceId);
                cdrEvent.getPayload().getAccounting().setBucketId(bucketId);
            }
        } catch (Exception e) {
            LoggingUtil.logError(log, M_CDR, e, "Error building CDR event for session: %s", request.sessionId());
            return Uni.createFrom().voidItem();
        }

        return accountProducer.produceAccountingCDREvent(cdrEvent)
                .onFailure().invoke(failure ->
                        LoggingUtil.logError(log, M_CDR, failure, "Failed to send CDR event for session: %s", request.sessionId()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
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
