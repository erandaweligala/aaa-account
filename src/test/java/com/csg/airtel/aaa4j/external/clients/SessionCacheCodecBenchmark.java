package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.ConsumptionRecord;
import com.csg.airtel.aaa4j.domain.model.session.QosParam;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing the pre-refactor cache codec (default Jackson JSON
 * with reflective accessors) against the new path (CBOR + Blackbird +
 * precompiled ObjectReader/ObjectWriter + NON_EMPTY).
 *
 * Run from the project root:
 *   mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 *   java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" \
 *        com.csg.airtel.aaa4j.external.clients.SessionCacheCodecBenchmark
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class SessionCacheCodecBenchmark {

    private UserSessionData fixture;
    private byte[] jsonBytes;
    private byte[] cborBytes;

    private ObjectMapper baselineJsonMapper;
    private CBORMapper tunedCborMapper;
    private ObjectReader tunedCborReader;
    private ObjectWriter tunedCborWriter;

    @Setup
    public void setup() throws Exception {
        fixture = buildFixture();

        // Mirrors the pre-refactor production path: Quarkus default ObjectMapper
        // with JavaTimeModule auto-registered, ISO date serialization, no
        // Blackbird, no precompiled reader/writer.
        baselineJsonMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        tunedCborMapper = CBORMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(new BlackbirdModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_EMPTY)
                .build();
        tunedCborReader = tunedCborMapper.readerFor(UserSessionData.class);
        tunedCborWriter = tunedCborMapper.writerFor(UserSessionData.class);

        jsonBytes = baselineJsonMapper.writeValueAsBytes(fixture);
        cborBytes = tunedCborWriter.writeValueAsBytes(fixture);

        System.out.println("JSON payload size: " + jsonBytes.length + " bytes");
        System.out.println("CBOR payload size: " + cborBytes.length + " bytes");
    }

    @Benchmark
    public byte[] encode_old_jsonReflectionAsBytes() throws Exception {
        return baselineJsonMapper.writeValueAsBytes(fixture);
    }

    @Benchmark
    public String encode_old_jsonReflectionAsString() throws Exception {
        return baselineJsonMapper.writeValueAsString(fixture);
    }

    @Benchmark
    public byte[] encode_new_cborBlackbirdPrecompiled() throws Exception {
        return tunedCborWriter.writeValueAsBytes(fixture);
    }

    @Benchmark
    public UserSessionData decode_old_jsonReflection() throws Exception {
        return baselineJsonMapper.readValue(jsonBytes, UserSessionData.class);
    }

    @Benchmark
    public UserSessionData decode_new_cborBlackbirdPrecompiled() throws Exception {
        return tunedCborReader.readValue(cborBytes);
    }

    private UserSessionData buildFixture() {
        UserSessionData u = new UserSessionData();
        u.setUserName("user_12345");
        u.setSessionTimeOut("3600");
        u.setUserStatus("ACTIVE");
        u.setGroupId("42");
        u.setConcurrency(2L);
        u.setSuperTemplateId(1L);

        List<Balance> balances = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            Balance b = new Balance();
            b.setInitialBalance(1_000_000_000L);
            b.setQuota(950_000_000L);
            b.setServiceExpiry(LocalDateTime.now().plusDays(30));
            b.setBucketExpiryDate(LocalDateTime.now().plusDays(30));
            b.setBucketId("bucket_" + i);
            b.setServiceId("svc_" + i);
            b.setPriority((long) i);
            b.setServiceStartDate(LocalDateTime.now().minusDays(5));
            b.setServiceStatus("ACTIVE");
            b.setTimeWindow("ALL_DAY");
            b.setConsumptionLimit(500_000_000L);
            b.setConsumptionLimitWindow(86400L);
            b.setBucketUsername("user_12345");
            b.setUnlimited(false);
            b.setGroup(false);
            b.setUsage(50_000_000L);
            b.setRecurring(true);
            b.setCycleStartDate(LocalDateTime.now().minusDays(5));
            List<ConsumptionRecord> hist = new ArrayList<>(5);
            for (int d = 0; d < 5; d++) {
                hist.add(new ConsumptionRecord(LocalDate.now().minusDays(d), 10_000_000L, 12));
            }
            b.setConsumptionHistory(hist);
            balances.add(b);
        }
        u.setBalance(balances);

        List<Session> sessions = new ArrayList<>(2);
        for (int i = 0; i < 2; i++) {
            Session s = new Session();
            s.setSessionId("sess_" + i);
            s.setSessionInitiatedTime(LocalDateTime.now().minusMinutes(30));
            s.setSessionStartTime(LocalDateTime.now().minusMinutes(29));
            s.setPreviousUsageBucketId("bucket_" + i);
            s.setSessionTime(1800);
            s.setPreviousTotalUsageQuotaValue(40_000_000L);
            s.setFramedId("framed_" + i);
            s.setNasIp("10.0.0.1");
            s.setNasPortId("port_" + i);
            s.setSessionUsage(10_000_000L);
            s.setAvailableBalance(900_000_000L);
            s.setGroupId("42");
            s.setUserName("user_12345");
            s.setServiceId("svc_" + i);
            s.setAbsoluteTimeOut("7200");
            s.setUserStatus("ACTIVE");
            s.setUserConcurrency(2L);
            sessions.add(s);
        }
        u.setSessions(sessions);

        QosParam q = new QosParam();
        q.setNormalBandwidth("10Mbps");
        q.setFupBandwidth("2Mbps");
        u.setQosParam(q);

        return u;
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(SessionCacheCodecBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.TEXT)
                .build();
        new Runner(opt).run();
    }
}
