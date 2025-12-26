package com.csg.airtel.aaa4j.application.resources;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.service.AccountingHandlerFactory;
import com.csg.airtel.aaa4j.domain.service.NotificationTrackingService;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.repository.UserBucketRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;



import java.util.HashMap;


import java.util.Map;



@Path("/debug")
@ApplicationScoped
public class RedisResource {

    final UserBucketRepository userRepository;

    final CacheClient cacheClient;

    final AccountingHandlerFactory accountingHandlerFactory;

    final NotificationTrackingService notificationTrackingService;

    public RedisResource(UserBucketRepository userRepository, CacheClient cacheClient, AccountingHandlerFactory accountingHandlerFactory, NotificationTrackingService notificationTrackingService) {
        this.userRepository = userRepository;
        this.cacheClient = cacheClient;
        this.accountingHandlerFactory = accountingHandlerFactory;
        this.notificationTrackingService = notificationTrackingService;
    }

    @GET
    @Path("/redis-ping")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<Map<String, Object>> testConnection() {

        return userRepository.getServiceBucketsByUserName("100001")
                .onItem().transform(buckets -> {
                    Map<String, Object> results = new HashMap<>();
                    results.put("buckets", buckets);
                    return results;
                });
    }


    @POST
    @Path("/redis-ping")
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Map<String, Object>> interimUpdate(AccountingRequestDto request) {

        return accountingHandlerFactory
                .getHandler(request,null)
                .onItem().transform(result -> {
                    Map<String, Object> res = new HashMap<>();
                    res.put("accounting_result", result);
                    return res;
                });
    }


    @DELETE
    @Path("/redis/delete")
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Map<String, Object>> deleteKeyCache(@QueryParam("username") String key) {

        return cacheClient
                .deleteKey(key)
                .onItem().transform(result -> {
                    Map<String, Object> res = new HashMap<>();
                    res.put("accounting_result", result);
                    return res;
                });
    }

    @GET
    @Path("/redis/get")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni <UserSessionData> getKeyCache(@QueryParam("username") String key) {

        return cacheClient
                .getUserData(key)
                .onItem().transform(result ->
                     result
                );
    }

    @GET
    @Path("/redis/notification-clear")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Map<String, Object>> deleteNotification(@QueryParam("username") String username,@QueryParam("templateId") long templateId,
                                                   @QueryParam("bucketId") String bucketId, @QueryParam("thresholdLevel") long thresholdLevel) {

        return notificationTrackingService
                .clearNotificationTracking(username,templateId,bucketId,thresholdLevel)
                .onItem().transform(result -> {
                    Map<String, Object> res = new HashMap<>();
                    res.put("Notification-clear", result);
                    return res;
                });
    }
}