package com.csg.airtel.aaa4j.domain.service;


import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;


@ApplicationScoped
public class AccountingHandlerFactory {
    private static final Logger LOG = Logger.getLogger(AccountingHandlerFactory.class);
    final StartHandler startHandler;
    final InterimHandler interimHandler;
    final StopHandler stopHandler;

    @Inject
    public AccountingHandlerFactory(StartHandler startHandler, InterimHandler interimHandler, StopHandler stopHandler) {
        this.startHandler = startHandler;
        this.interimHandler = interimHandler;
        this.stopHandler = stopHandler;
    }

    public Uni<Void> getHandler(AccountingRequestDto request,String traceId) {
        return getHandler(request, traceId, false);
    }

    public Uni<Void> getHandler(AccountingRequestDto request,String traceId, boolean skipInMemoryCache) {
        LOG.infof("[traceId: %s] Received accounting request for user: %s with action type: %s, skipInMemoryCache: %s",
                traceId, request.username(), request.actionType(), skipInMemoryCache);
        return switch (request.actionType()) {
            case START -> startHandler.processAccountingStart(request,traceId);
            case INTERIM_UPDATE -> interimHandler.handleInterim(request,traceId, skipInMemoryCache);
            case STOP -> stopHandler.stopProcessing(request, null,traceId);
        };
    }

}
