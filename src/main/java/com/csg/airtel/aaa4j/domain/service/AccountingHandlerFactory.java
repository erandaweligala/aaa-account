package com.csg.airtel.aaa4j.domain.service;


import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;


@ApplicationScoped
public class AccountingHandlerFactory {
    private static final Logger LOG = Logger.getLogger(AccountingHandlerFactory.class);
    private static final String CLASS_NAME = "AccountingHandlerFactory";
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
        LoggingUtil.logInfo(LOG, CLASS_NAME, "getHandler", "[traceId: %s] Received accounting request for user: %s with action type: %s",
                traceId, request.username(), request.actionType());
        return switch (request.actionType()) {
            case START -> startHandler.processAccountingStart(request,traceId);
            case INTERIM_UPDATE -> interimHandler.handleInterim(request,traceId);
            case STOP -> stopHandler.stopProcessing(request, null,traceId);
        };
    }

}
